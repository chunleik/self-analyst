# 构建包含后端 JAR 与精简 JRE 的 Windows NSIS 安装包。

param(
    [switch]$SkipBuild,
    [switch]$SkipInstall
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$Root = [System.IO.Path]::GetFullPath($Root).TrimEnd('\')
$Portable = Join-Path $Root "dist-portable"
$BundleResources = Join-Path $Root "self-analyst-desktop/src-tauri/bundle-resources"
$Desktop = Join-Path $Root "self-analyst-desktop"
$Artifacts = Join-Path $Root "artifacts"

function Assert-WorkspaceChild([string]$Path, [string]$Name) {
    $resolved = [System.IO.Path]::GetFullPath($Path)
    if (-not $resolved.StartsWith($Root + '\', [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "$Name 不在工作区内: $resolved"
    }
    return $resolved
}

$BundleResources = Assert-WorkspaceChild $BundleResources "Tauri bundle resources"
$Artifacts = Assert-WorkspaceChild $Artifacts "构建产物目录"

if (-not $SkipBuild) {
    & (Join-Path $Root "scripts/build-portable.ps1") -NoZip
}

$JarSource = Join-Path $Portable "self-analyst-app.jar"
$RuntimeSource = Join-Path $Portable "runtime"
if (-not (Test-Path -LiteralPath $JarSource -PathType Leaf)) {
    throw "缺少后端 JAR: $JarSource"
}
if (-not (Test-Path -LiteralPath (Join-Path $RuntimeSource "bin/java.exe") -PathType Leaf)) {
    throw "缺少便携 Java runtime: $RuntimeSource"
}

New-Item -ItemType Directory -Force -Path $BundleResources | Out-Null
$StagedJar = Join-Path $BundleResources "self-analyst-app.jar"
$StagedRuntime = Join-Path $BundleResources "runtime"
Remove-Item -LiteralPath $StagedJar -Force -ErrorAction SilentlyContinue
if (Test-Path -LiteralPath $StagedRuntime) {
    $StagedRuntime = Assert-WorkspaceChild $StagedRuntime "暂存 runtime"
    Remove-Item -LiteralPath $StagedRuntime -Recurse -Force
}
Copy-Item -LiteralPath $JarSource -Destination $StagedJar
Copy-Item -LiteralPath $RuntimeSource -Destination $StagedRuntime -Recurse

$Marker = Join-Path $BundleResources "installed-layout.marker"
if (-not (Test-Path -LiteralPath $Marker -PathType Leaf)) {
    throw "缺少安装布局标记: $Marker"
}

Push-Location $Desktop
try {
    if (-not $SkipInstall) {
        $PreviousCi = $env:CI
        $env:CI = "true"
        try {
            pnpm install --frozen-lockfile
            if ($LASTEXITCODE -ne 0) { throw "pnpm install 失败" }
        } finally {
            if ($null -eq $PreviousCi) {
                Remove-Item Env:CI -ErrorAction SilentlyContinue
            } else {
                $env:CI = $PreviousCi
            }
        }
    }
    $Tauri = Join-Path $Desktop "node_modules/.bin/tauri.cmd"
    if (-not (Test-Path -LiteralPath $Tauri -PathType Leaf)) {
        throw "缺少 Tauri CLI，请先运行 pnpm install: $Tauri"
    }
    & $Tauri build
    if ($LASTEXITCODE -ne 0) { throw "Tauri NSIS 构建失败" }
} finally {
    Pop-Location
}

$NsisDirectory = Join-Path $Root "self-analyst-desktop/src-tauri/target/release/bundle/nsis"
$Installer = Get-ChildItem -LiteralPath $NsisDirectory -Filter "*-setup.exe" -File |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
if (-not $Installer) {
    throw "未找到 NSIS 安装包: $NsisDirectory"
}

New-Item -ItemType Directory -Force -Path $Artifacts | Out-Null
$ArtifactInstaller = Join-Path $Artifacts $Installer.Name
Copy-Item -LiteralPath $Installer.FullName -Destination $ArtifactInstaller -Force
$Hash = Get-FileHash -LiteralPath $ArtifactInstaller -Algorithm SHA256
$ChecksumPath = $ArtifactInstaller + ".sha256"
[System.IO.File]::WriteAllText(
    $ChecksumPath,
    "$($Hash.Hash.ToLowerInvariant())  $($Installer.Name)`n",
    [System.Text.UTF8Encoding]::new($false))

Write-Host "NSIS 安装包已生成: $ArtifactInstaller" -ForegroundColor Green
Write-Host "SHA-256: $($Hash.Hash.ToLowerInvariant())" -ForegroundColor DarkGray
