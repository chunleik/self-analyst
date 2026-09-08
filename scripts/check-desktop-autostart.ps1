# 验证正式桌面壳的静默入口、单实例及带中文空格的便携路径。
# 只使用临时数据，关闭全部采集与远程功能；不写入系统启动项。
param(
    [string]$DesktopPath = 'self-analyst-desktop/src-tauri/target/release/SelfAnalyst.exe',
    [string]$DistributionPath = 'dist-portable',
    [switch]$KeepRunning
)
$ErrorActionPreference = 'Stop'
$Root = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot)).TrimEnd('\')
if (-not [IO.Path]::IsPathRooted($DesktopPath)) { $DesktopPath = Join-Path $Root $DesktopPath }
if (-not [IO.Path]::IsPathRooted($DistributionPath)) { $DistributionPath = Join-Path $Root $DistributionPath }
if (Get-Process SelfAnalyst -ErrorAction SilentlyContinue) { throw '请先退出已有 SelfAnalyst，再运行隔离冒烟测试' }
$Scratch = [IO.Path]::GetFullPath((Join-Path $Root ('.tmp\autostart-' + [guid]::NewGuid().ToString('N'))))
if (-not $Scratch.StartsWith($Root + '\.tmp\autostart-', [StringComparison]::OrdinalIgnoreCase)) { throw '临时目录越界' }
$Portable = Join-Path $Scratch '便携 路径'
New-Item -ItemType Directory -Path $Portable -Force | Out-Null
Copy-Item -LiteralPath $DesktopPath -Destination (Join-Path $Portable 'SelfAnalyst.exe')
Copy-Item -LiteralPath (Join-Path $DistributionPath 'self-analyst-app.jar') -Destination $Portable
Copy-Item -LiteralPath (Join-Path $DistributionPath 'runtime') -Destination $Portable -Recurse
$Config = Join-Path $Portable 'data/config'
New-Item -ItemType Directory -Path $Config -Force | Out-Null
$Socket = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
$Socket.Start()
$Port = $Socket.LocalEndpoint.Port
$Socket.Stop()
$TestConfig = @"
[aw]
port = $Port
[aw.collection]
window = false
afk = false
content = false
[file.watch]
enabled = false
[wiki]
enabled = false
[embedding]
enabled = false
[websearch]
enabled = false
[llm]
api-key = ''
"@
# 同时落盘，确保手动重开临时实例时仍保持测试配置。
[IO.File]::WriteAllText((Join-Path $Config 'config.toml'), $TestConfig, [Text.UTF8Encoding]::new($false))
$Report = [Collections.Generic.List[string]]::new()
$Primary = $null
$Retain = $false
$ShellLogs = @{}

function Start-TestDesktop([string[]]$Arguments) {
    $info = [Diagnostics.ProcessStartInfo]::new()
    $info.FileName = Join-Path $Portable 'SelfAnalyst.exe'
    $info.WorkingDirectory = $Root # 特意使用分发以外目录。
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $info.WindowStyle = [Diagnostics.ProcessWindowStyle]::Hidden
    foreach ($argument in $Arguments) { $info.ArgumentList.Add($argument) }
    foreach ($setting in @('AW_COLLECTION_WINDOW', 'AW_COLLECTION_AFK', 'AW_COLLECTION_CONTENT',
            'FILE_WATCH_ENABLED', 'WIKI_ENABLED', 'EMBEDDING_ENABLED', 'WEBSEARCH_ENABLED')) {
        $info.Environment[$setting] = 'false'
    }
    $info.Environment['AW_MODE'] = 'embedded'
    $info.Environment['AW_DATA_DIR'] = Join-Path $Portable 'data/aw-data'
    $info.Environment['AW_RAW_DIR'] = Join-Path $Portable 'data/aw-data/raw'
    $info.Environment['MEMORY_DIR'] = Join-Path $Portable 'data/memory'
    $info.Environment['OPENAI_API_KEY'] = ''
    $process = [Diagnostics.Process]::Start($info)
    $ShellLogs[$process.Id] = @($process.StandardOutput.ReadToEndAsync(), $process.StandardError.ReadToEndAsync())
    return $process
}

function Wait-DesktopWindow([bool]$Visible) {
    $deadline = [DateTime]::UtcNow.AddSeconds(65)
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($Primary.HasExited) { throw "桌面进程提前退出: $($Primary.ExitCode)" }
        $Primary.Refresh()
        $log = Join-Path $Portable 'self-analyst-backend.log'
        $ready = (Test-Path -LiteralPath $log) -and (Select-String -LiteralPath $log -Pattern 'Desktop UI 已就绪' -Quiet)
        if ($ready -and (($Primary.MainWindowTitle -eq 'SelfAnalyst') -eq $Visible)) { return }
        Start-Sleep -Milliseconds 100
    }
    throw "窗口状态未达到预期，visible=$Visible"
}

function Invoke-Duplicate([bool]$Automatic) {
    $arguments = if ($Automatic) { @('--autostart') } else { @() }
    $duplicate = Start-TestDesktop $arguments
    try {
        if (-not $duplicate.WaitForExit(10000)) { $duplicate.Kill(); throw '重复启动未在期限内退出' }
        if ($duplicate.ExitCode -ne 0) { throw '重复启动退出异常' }
    } finally { $duplicate.Dispose() }
}

function Stop-TestDesktop {
    if ($Primary -and -not $Primary.HasExited) {
        $Primary.Kill() # 验证 Job Object 对受管 Java 的回收。
        $Primary.WaitForExit()
        Start-Sleep -Milliseconds 500
    }
    if ($Primary) { $Primary.Dispose() }
    foreach ($entry in $ShellLogs.GetEnumerator()) {
        if ($entry.Value[0].IsCompleted -and $entry.Value[1].IsCompleted) {
            ($entry.Value | ForEach-Object { $_.GetAwaiter().GetResult() }) |
                Set-Content -LiteralPath (Join-Path $Scratch ("shell-" + $entry.Key + '.log')) -Encoding utf8
        }
    }
}

try {
    $Primary = Start-TestDesktop @('--autostart')
    Wait-DesktopWindow $false
    Write-Host '后端已就绪，开始检查隐藏窗口与重复启动。'
    # 后端就绪与 WebView 创建之间仍可能有短暂间隔；持续观察避免漏掉闪现。
    for ($i = 0; $i -lt 30; $i++) {
        $Primary.Refresh()
        if ($Primary.MainWindowTitle -eq 'SelfAnalyst') { throw '自动启动显示了主窗口' }
        Start-Sleep -Milliseconds 100
    }
    Invoke-Duplicate $true
    $Primary.Refresh()
    if ($Primary.MainWindowTitle -eq 'SelfAnalyst') { throw '自动重复启动改变了窗口状态' }
    $Report.Add('通过：中文空格便携路径、不同工作目录、自动启动隐藏及自动重复启动静默退出。')
    Invoke-Duplicate $false
    Write-Host '已发出手动唤起请求，等待主窗口。'
    Wait-DesktopWindow $true
    $Report.Add('通过：手动重复启动唤起已有主窗口。')
    $children = @(Get-Process java -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq (Join-Path $Portable 'runtime/bin/java.exe') })
    if ($children.Count -ne 1) { throw "受管 Java 数量异常: $($children.Count)" }
    $Report.Add('通过：只有一个受管 Java 后端。')
    if ($KeepRunning) {
        $Retain = $true
        $Report.Add("保留运行供界面检查：PID=$($Primary.Id)，目录=$Portable")
    } else {
        Stop-TestDesktop
        $Primary = $null
        $Moved = [IO.Path]::GetFullPath((Join-Path $Scratch '移动后 路径'))
        if (-not $Moved.StartsWith($Scratch + '\', [StringComparison]::OrdinalIgnoreCase)) { throw '移动目标越界' }
        Move-Item -LiteralPath $Portable -Destination $Moved
        $Portable = $Moved
        $Primary = Start-TestDesktop @('--autostart')
        Invoke-Duplicate $false # 特意在首实例尚未就绪时请求显示。
        Wait-DesktopWindow $true
        $Report.Add('通过：移动目录后仍可启动，初始化期间的手动唤起请求被保留。')
        Stop-TestDesktop
        $Primary = $null
        [IO.File]::WriteAllText((Join-Path $Portable 'self-analyst-app.jar'), 'invalid jar')
        $Primary = Start-TestDesktop @('--autostart')
        if (-not $Primary.WaitForExit(65000)) { throw '后端故障后桌面壳未退出' }
        if ($Primary.ExitCode -eq 0) { throw '后端故障退出码不应为零' }
        if (-not (Select-String -LiteralPath (Join-Path $Portable 'self-analyst-backend.log') -Pattern 'Invalid or corrupt jarfile' -Quiet)) {
            throw '后端故障缺少诊断日志'
        }
        $Report.Add('通过：后端故障退出并保留诊断日志。')
    }
    $Report | Set-Content -LiteralPath (Join-Path $Scratch 'result.txt') -Encoding utf8
    $Report | Write-Host
    Write-Host "验证记录：$Scratch\result.txt"
} finally {
    if (-not $Retain) { Stop-TestDesktop }
    # 保留独立目录和诊断记录，便于失败复查；不删除用户数据。
}
