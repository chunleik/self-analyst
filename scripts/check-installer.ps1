# 静默安装、验证资源、运行后端冒烟并卸载 NSIS 安装包。
# 安装目录固定在工作区 .tmp 下，结束时会验证并清理残留。

param(
    [string]$InstallerPath,
    [int]$ProcessTimeoutSeconds = 180
)

$ErrorActionPreference = "Stop"
$Root = [System.IO.Path]::GetFullPath(
    (Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path))).TrimEnd('\')
$Artifacts = Join-Path $Root "artifacts"

if ([string]::IsNullOrWhiteSpace($InstallerPath)) {
    $Installer = Get-ChildItem -LiteralPath $Artifacts -Filter "*-setup.exe" -File |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if (-not $Installer) { throw "artifacts 中没有 NSIS 安装包" }
    $InstallerPath = $Installer.FullName
} elseif (-not [System.IO.Path]::IsPathRooted($InstallerPath)) {
    $InstallerPath = Join-Path $Root $InstallerPath
}
$InstallerPath = [System.IO.Path]::GetFullPath($InstallerPath)
if (-not (Test-Path -LiteralPath $InstallerPath -PathType Leaf)) {
    throw "缺少 NSIS 安装包: $InstallerPath"
}

$ScratchBase = [System.IO.Path]::GetFullPath((Join-Path $Root ".tmp")).TrimEnd('\')
New-Item -ItemType Directory -Path $ScratchBase -Force | Out-Null
$InstallRoot = [System.IO.Path]::GetFullPath(
    (Join-Path $ScratchBase ("nsis-install-" + [guid]::NewGuid().ToString("N"))))
if (-not $InstallRoot.StartsWith($ScratchBase + '\', [System.StringComparison]::OrdinalIgnoreCase) -or
    -not ([System.IO.Path]::GetFileName($InstallRoot)).StartsWith("nsis-install-")) {
    throw "拒绝使用不安全的安装测试目录: $InstallRoot"
}

function Invoke-HiddenProcess {
    param(
        [string]$FilePath,
        [string[]]$Arguments
    )

    $info = [System.Diagnostics.ProcessStartInfo]::new()
    $info.FileName = $FilePath
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    foreach ($argument in $Arguments) {
        $info.ArgumentList.Add($argument)
    }
    $process = [System.Diagnostics.Process]::Start($info)
    try {
        if (-not $process.WaitForExit($ProcessTimeoutSeconds * 1000)) {
            $process.Kill($true)
            $process.WaitForExit()
            throw "进程超时: $FilePath"
        }
        if ($process.ExitCode -ne 0) {
            throw "进程退出码为 $($process.ExitCode): $FilePath"
        }
    } finally {
        $process.Dispose()
    }
}

try {
    Invoke-HiddenProcess -FilePath $InstallerPath -Arguments @("/S", "/D=$InstallRoot")

    $DesktopExe = Join-Path $InstallRoot "SelfAnalyst.exe"
    $Jar = Join-Path $InstallRoot "self-analyst-app.jar"
    $Java = Join-Path $InstallRoot "runtime/bin/java.exe"
    $Marker = Join-Path $InstallRoot "installed-layout.marker"
    foreach ($required in @($DesktopExe, $Jar, $Java, $Marker)) {
        if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
            throw "安装结果缺少必需文件: $required"
        }
    }
    $FirstJarHash = (Get-FileHash -LiteralPath $Jar -Algorithm SHA256).Hash

    # 同版本静默重装走与升级相同的资源替换路径，必须保持完整布局。
    Invoke-HiddenProcess -FilePath $InstallerPath -Arguments @("/S", "/D=$InstallRoot")
    foreach ($required in @($DesktopExe, $Jar, $Java, $Marker)) {
        if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
            throw "重装结果缺少必需文件: $required"
        }
    }
    $SecondJarHash = (Get-FileHash -LiteralPath $Jar -Algorithm SHA256).Hash
    if ($SecondJarHash -ne $FirstJarHash) {
        throw "同版本重装改变了后端 JAR 内容"
    }

    & (Join-Path $Root "scripts/check-packaged-jar.ps1") -JarPath $Jar -JavaPath $Java

    $Uninstaller = Get-ChildItem -LiteralPath $InstallRoot -Filter "uninstall*.exe" -File |
        Select-Object -First 1
    if (-not $Uninstaller) {
        throw "安装结果缺少卸载程序: $InstallRoot"
    }
    Invoke-HiddenProcess -FilePath $Uninstaller.FullName -Arguments @("/S")

    $deadline = [DateTime]::UtcNow.AddSeconds(15)
    while ([DateTime]::UtcNow -lt $deadline -and (Test-Path -LiteralPath $DesktopExe)) {
        Start-Sleep -Milliseconds 200
    }
    if (Test-Path -LiteralPath $DesktopExe) {
        throw "静默卸载后桌面可执行文件仍然存在: $DesktopExe"
    }

    Write-Host "NSIS 安装、重装、资源、后端与卸载验证通过: $InstallerPath" -ForegroundColor Green
} finally {
    if (Test-Path -LiteralPath $InstallRoot) {
        Remove-Item -LiteralPath $InstallRoot -Recurse -Force
    }
}
