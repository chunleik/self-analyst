# 解析 self-analyst-app 的可执行 JAR 路径，避免各脚本硬编码版本号。
# original-*.jar 由前缀匹配排除；*-shaded.jar 是 Shade 中间产物，不能作为分发候选。

param(
    [string]$TargetDir
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($TargetDir)) {
    $Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
    $TargetDir = Join-Path $Root "self-analyst-app/target"
}

if (-not (Test-Path -LiteralPath $TargetDir -PathType Container)) {
    throw "缺少构建输出目录，请先运行 mvn package: $TargetDir"
}

$candidates = @(
    Get-ChildItem -LiteralPath $TargetDir -Filter "self-analyst-app-*.jar" -File |
        Where-Object { $_.Name -notlike '*-shaded.jar' } |
        Sort-Object -Property Name
)

if ($candidates.Count -eq 0) {
    throw "未找到可执行 JAR，请先运行 mvn package: $TargetDir/self-analyst-app-*.jar"
}
if ($candidates.Count -gt 1) {
    $names = ($candidates | ForEach-Object { $_.Name }) -join ", "
    throw "构建输出中存在多个可执行 JAR，请清理后重试: $names"
}

$candidates[0].FullName
