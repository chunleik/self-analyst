# 默认只评估仓库中的脱敏标题夹具；只有 -Live 才请求模型。
param(
    [switch]$Live,
    [ValidateSet('Formal', 'DiagnosticHttp')]
    [string]$Transport = 'Formal',
    [ValidateSet('Current', 'Baseline', 'Both')]
    [string]$Strategy = 'Current',
    [switch]$FullHistory,
    [ValidateRange(1, 100)]
    [int]$Repeat = 3,
    [int[]]$Budgets = @(),
    [string]$Case,
    [ValidateRange(4000, 1000000)]
    [int]$RequestChars = 32000,
    [ValidateRange(1, 16)]
    [int]$MaxCalls = 6,
    [ValidateRange(1, 10000)]
    [int]$MaxTotalCalls = 300,
    [ValidateRange(0, 32768)]
    [int]$MaxOutputTokens = 0,
    [ValidateRange(1, 3600)]
    [int]$TimeoutSeconds = 60,
    [string]$Output,
    [string]$JavaHome,
    [string]$MavenSettings,
    [switch]$MavenOffline,
    [switch]$SkipBuild,
    [string]$ClassPathFile
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$moduleRoot = Join-Path $repositoryRoot 'self-analyst-wiki'
$formalRuntime = $Live -and $Transport -eq 'Formal'
$buildModule = if ($formalRuntime) { 'self-analyst-app' } else { 'self-analyst-wiki' }
if ([string]::IsNullOrWhiteSpace($Output)) {
    $Output = Join-Path $moduleRoot 'target/wiki-quality-evaluation.json'
} elseif (-not [System.IO.Path]::IsPathRooted($Output)) {
    $Output = Join-Path $repositoryRoot $Output
}
if ([string]::IsNullOrWhiteSpace($ClassPathFile)) {
    $ClassPathFile = Join-Path (Join-Path $repositoryRoot $buildModule) 'target/wiki-quality-classpath.txt'
} elseif (-not [System.IO.Path]::IsPathRooted($ClassPathFile)) {
    $ClassPathFile = Join-Path $repositoryRoot $ClassPathFile
}
$Output = [System.IO.Path]::GetFullPath($Output)
$ClassPathFile = [System.IO.Path]::GetFullPath($ClassPathFile)
$originalJavaHome = $env:JAVA_HOME
$originalPath = $env:PATH

try {
    if (-not [string]::IsNullOrWhiteSpace($JavaHome)) {
        $env:JAVA_HOME = [System.IO.Path]::GetFullPath($JavaHome)
        $env:PATH = (Join-Path $env:JAVA_HOME 'bin') + [System.IO.Path]::PathSeparator + $env:PATH
    }
    $javaCommand = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { (Get-Command java -ErrorAction Stop).Source }
    if (-not (Test-Path -LiteralPath $javaCommand -PathType Leaf)) {
        throw '缺少 Java 运行时；请通过 -JavaHome 指定 JDK 21。'
    }
    if ($Budgets | Where-Object { $_ -lt 1 -or $_ -gt 1000000 }) {
        throw 'Budgets 中的每个事实预算必须为 1..1000000 字符。'
    }
    if ($FullHistory -and ($Strategy -ne 'Current' -or ($Budgets | Where-Object { $_ -lt 1000 }))) {
        throw '-FullHistory 只用于 Current；显式事实预算必须不少于 1000 字符。'
    }
    if ($Transport -eq 'Formal' -and $MaxOutputTokens -ne 0) {
        throw '正式 PlainTask 不发送输出上限；-MaxOutputTokens 仅可用于显式 -Transport DiagnosticHttp。'
    }
    if ($Live) {
        foreach ($name in @('WIKI_EVAL_API_KEY', 'WIKI_EVAL_BASE_URL', 'WIKI_EVAL_MODEL')) {
            if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name))) {
                throw "启用真实评测前必须设置环境变量 $name；不会读取用户配置。"
            }
        }
    }
    Push-Location $repositoryRoot
    try {
        if (-not $SkipBuild) {
            $mavenArguments = @('-pl', $buildModule, '-am', '-DskipTests', 'package',
                'dependency:build-classpath', '-DincludeScope=test', "-Dmdep.outputFile=$ClassPathFile")
            if ($MavenOffline) { $mavenArguments = @('-o') + $mavenArguments }
            if (-not [string]::IsNullOrWhiteSpace($MavenSettings)) {
                $mavenArguments = @('-s', [System.IO.Path]::GetFullPath($MavenSettings)) + $mavenArguments
            }
            & mvn @mavenArguments
            if ($LASTEXITCODE -ne 0) { throw "评测编译或 classpath 生成失败，退出码 $LASTEXITCODE。" }
        }
        if (-not (Test-Path -LiteralPath $ClassPathFile -PathType Leaf)) {
            throw '缺少评测 classpath；先去掉 -SkipBuild 构建，或传入已生成的 -ClassPathFile。'
        }
        $moduleClasses = Get-ChildItem -LiteralPath $repositoryRoot -Directory -Filter 'self-analyst-*' |
            ForEach-Object { Join-Path $_.FullName 'target/classes' } |
            Where-Object { Test-Path -LiteralPath $_ -PathType Container }
        $classpath = (@(
            (Join-Path $moduleRoot 'target/test-classes'),
            (Join-Path $repositoryRoot 'self-analyst-app/target/test-classes')
        ) + $moduleClasses + @(
            (Get-Content -LiteralPath $ClassPathFile -Raw).Trim()
        )) -join [System.IO.Path]::PathSeparator
        $transportArgument = if ($Transport -eq 'Formal') { 'formal' } else { 'diagnostic-http' }
        $javaArguments = @('-Dorg.slf4j.simpleLogger.defaultLogLevel=error', '-cp', $classpath,
            'com.selfanalyst.wiki.WikiQualityEvaluation', '--repeat', "$Repeat", '--request-chars', "$RequestChars",
            '--max-calls', "$MaxCalls", '--max-total-calls', "$MaxTotalCalls", '--timeout-seconds', "$TimeoutSeconds",
            '--transport', $transportArgument,
            '--output', $Output)
        if ($formalRuntime) { $javaArguments = @('-Dlogback.configurationFile=wiki-eval-logback.xml') + $javaArguments }
        if ($MaxOutputTokens -gt 0) { $javaArguments += @('--max-output-tokens', "$MaxOutputTokens") }
        if ($Live) { $javaArguments += '--live' }
        if ($FullHistory) { $javaArguments += '--full-history' }
        if ($Strategy -in @('Baseline', 'Both')) { $javaArguments += '--baseline' }
        if ($Strategy -in @('Current', 'Both')) { $javaArguments += '--current' }
        if ($Budgets.Count -gt 0) { $javaArguments += @('--budgets', ($Budgets -join ',')) }
        if (-not [string]::IsNullOrWhiteSpace($Case)) { $javaArguments += @('--case', $Case) }
        & $javaCommand @javaArguments
        if ($LASTEXITCODE -ne 0) { throw "评测入口失败，退出码 $LASTEXITCODE；错误不会包含凭据或模型错误响应正文。" }
    } finally {
        Pop-Location
    }
} finally {
    $env:JAVA_HOME = $originalJavaHome
    $env:PATH = $originalPath
}
