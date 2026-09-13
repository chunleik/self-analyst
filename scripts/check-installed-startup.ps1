# 真实安装布局启动验证；已有应用数据或运行实例时拒绝测试。
# 只创建本次测试数据，结束时凭所有权标记清理；不修改既有配置。
param([Parameter(Mandatory)][string]$DistributionPath, [int]$TimeoutSeconds = 65)
$ErrorActionPreference = 'Stop'
$DistributionPath = [IO.Path]::GetFullPath($DistributionPath)
foreach ($name in @('SelfAnalyst.exe', 'self-analyst-app.jar', 'installed-layout.marker', 'runtime/bin/java.exe')) {
    if (-not (Test-Path -LiteralPath (Join-Path $DistributionPath $name) -PathType Leaf)) { throw "安装布局缺少 $name" }
}
if (Get-Process SelfAnalyst -ErrorAction SilentlyContinue) { throw '请先退出已有桌面实例。' }
$UserRoot = Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'com.selfanalyst.desktop'
$Data = [IO.Path]::GetFullPath((Join-Path $UserRoot 'data'))
if (Test-Path -LiteralPath $Data) { throw "拒绝触碰既有应用数据：$Data；请在干净测试用户下运行。" }
$Root = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot)).TrimEnd('\')
$Scratch = Join-Path $Root ('.tmp/installed-startup-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $Scratch -Force | Out-Null
$Ownership = [guid]::NewGuid().ToString('N')
$OwnerFile = Join-Path $UserRoot ('.startup-smoke-owner-' + $Ownership)
$Process = $null
$CreatedData = $false
try {
    New-Item -ItemType Directory -Path $Data -ErrorAction Stop | Out-Null
    $CreatedData = $true
    [IO.File]::WriteAllText($OwnerFile, $Ownership)
    $Config = Join-Path $Data 'config'
    New-Item -ItemType Directory -Path $Config | Out-Null
    $Socket = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    $Socket.Start()
    $Port = $Socket.LocalEndpoint.Port
    $Socket.Stop()
    [IO.File]::WriteAllText((Join-Path $Config 'config.toml'), "[events]`nport = $Port`n", [Text.UTF8Encoding]::new($false))
    $Info = [Diagnostics.ProcessStartInfo]::new()
    $Info.FileName = Join-Path $DistributionPath 'SelfAnalyst.exe'
    $Info.WorkingDirectory = $Scratch
    $Info.ArgumentList.Add('--autostart')
    $Info.UseShellExecute = $false
    $Info.CreateNoWindow = $true
    $Info.WindowStyle = [Diagnostics.ProcessWindowStyle]::Hidden
    # 壳日志使用测试目录；Tauri 的数据根仍由 Windows Known Folder 决定。
    $Info.Environment['LOCALAPPDATA'] = $Scratch
    foreach ($key in @('EVENTS_COLLECTION_WINDOW', 'EVENTS_COLLECTION_AFK', 'EVENTS_COLLECTION_TITLE_ENABLED',
        'FILE_WATCH_ENABLED', 'WIKI_ENABLED', 'EMBEDDING_ENABLED', 'WEBSEARCH_ENABLED')) { $Info.Environment[$key] = 'false' }
    $Info.Environment['EVENTS_MODE'] = 'embedded'
    $Info.Environment['EVENTS_DATA_DIR'] = Join-Path $Data 'aw-data'
    $Info.Environment['EVENTS_RAW_DIR'] = Join-Path $Data 'aw-data/raw'
    $Info.Environment['MEMORY_DIR'] = Join-Path $Data 'memory'
    $Info.Environment['OPENAI_API_KEY'] = ''
    $Process = [Diagnostics.Process]::Start($Info)
    $Log = Join-Path $Scratch 'com.selfanalyst.desktop/self-analyst-shell.log'
    $Deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    $Ready = $false
    while ([DateTime]::UtcNow -lt $Deadline) {
        if ($Process.HasExited) { throw "安装壳提前退出：$($Process.ExitCode)" }
        if (Test-Path -LiteralPath $Log) {
            $Text = Get-Content -LiteralPath $Log -Raw
            if ($Text.Contains("pid=$($Process.Id) distribution resolved installed=true") -and
                $Text.Contains("pid=$($Process.Id) backend healthy") -and
                $Text.Contains("pid=$($Process.Id) main window created automatic=true")) {
                $Ready = $true
                break
            }
        }
        Start-Sleep -Milliseconds 200
    }
    if (-not $Ready) { throw "安装壳未完成资源选择、健康握手和窗口创建；日志：$Log" }
    Write-Host "安装布局桌面启动验证通过：$DistributionPath；日志：$Log" -ForegroundColor Green
} finally {
    if ($Process) {
        if (-not $Process.HasExited) { $Process.Kill($true); $Process.WaitForExit() }
        $Process.Dispose()
    }
    if ($CreatedData -and (Test-Path -LiteralPath $OwnerFile) -and
        (Get-Content -LiteralPath $OwnerFile -Raw) -ceq $Ownership) {
        $Expected = [IO.Path]::GetFullPath((Join-Path $UserRoot 'data'))
        if ($Data -cne $Expected -or (Get-Item -LiteralPath $Data).Attributes.HasFlag([IO.FileAttributes]::ReparsePoint)) {
            throw '拒绝清理路径发生变化的测试数据目录'
        }
        Remove-Item -LiteralPath $Data -Recurse -Force
        Remove-Item -LiteralPath $OwnerFile -Force
    }
}
