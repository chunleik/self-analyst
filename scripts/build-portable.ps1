# SelfAnalyst — fully-offline portable ("green") build.
#
# Produces dist-portable/ : a self-contained folder that needs NO system Java
# and NO Python/ActivityWatch (the AW engine is embedded in the jar). Then zips it.
# WebView2 is NOT bundled — the app uses the system-provided (Evergreen) runtime,
# which is preinstalled on Windows 10/11.
#
# Layout produced:
#   dist-portable/
#     SelfAnalyst.exe            Tauri shell (launches the backend)
#     self-analyst-app.jar       backend fat jar (embedded AW + agent + UI)
#     runtime/                   jlink'd minimal JRE (java.exe under runtime/bin)
#     portable.marker            用户可选添加，启用程序旁数据目录
#
# Prereqs: JDK 21 (jlink), Maven, and Rust/cargo.

param(
    [switch]$SkipBuild,   # reuse existing jar/exe instead of rebuilding
    [switch]$NoZip,       # leave the folder, don't produce the .zip
    [string]$OutputDirectory = 'dist-portable'
)

$ErrorActionPreference = "Stop"
$Root = [System.IO.Path]::GetFullPath(
    (Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path))).TrimEnd('\')
Set-Location $Root

$Dist = if ([IO.Path]::IsPathRooted($OutputDirectory)) { $OutputDirectory } else { Join-Path $Root $OutputDirectory }
$Artifacts = Join-Path $Root "artifacts"

function Assert-WorkspaceChild([string]$Path, [string]$Name) {
    $resolved = [System.IO.Path]::GetFullPath($Path)
    if (-not $resolved.StartsWith($Root + '\', [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "$Name 不在工作区内: $resolved"
    }
    return $resolved
}

$Dist = Assert-WorkspaceChild $Dist "便携分发目录"
if (Test-Path -LiteralPath (Join-Path $Dist 'data')) {
    throw '输出目录包含运行数据，请使用 -OutputDirectory 指定新的构建目录；不会删除既有数据。'
}
$Artifacts = Assert-WorkspaceChild $Artifacts "构建产物目录"

# ── Resolve a JDK 21 with jlink ────────────────────────────────────────────────
function Resolve-Jdk {
    $candidates = @()
    if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
    $candidates += @(
        "$env:ProgramFiles\Java\jdk-21",
        "$env:ProgramFiles\Eclipse Adoptium\jdk-21*",
        "$env:ProgramFiles\Microsoft\jdk-21*"
    )
    foreach ($c in $candidates) {
        foreach ($resolved in (Resolve-Path -Path $c -ErrorAction SilentlyContinue)) {
            if (Test-Path -LiteralPath (Join-Path $resolved.Path "bin\jlink.exe")) {
                return $resolved.Path
            }
        }
    }
    throw "No JDK 21 with jlink found. Set JAVA_HOME to a JDK 21 install."
}
$Jdk = Resolve-Jdk
Write-Host "Using JDK: $Jdk" -ForegroundColor DarkGray

# ── 1. Build jar + exe ─────────────────────────────────────────────────────────
if (-not $SkipBuild) {
    Write-Host "=== 1/6 Building Java jar ===" -ForegroundColor Cyan
    & (Join-Path $Root "scripts/build-axsidecar.ps1")
    mvn package -DskipTests -q
    if ($LASTEXITCODE -ne 0) { throw "Maven build failed" }

    Write-Host "=== 2/6 Building Tauri exe ===" -ForegroundColor Cyan
    Push-Location (Join-Path $Root "self-analyst-desktop/src-tauri")
    try {
        cargo build --release --quiet
        if ($LASTEXITCODE -ne 0) { throw "Cargo build failed" }
    } finally { Pop-Location }
} else {
    Write-Host "=== 1-2/6 SkipBuild: reusing existing jar + exe ===" -ForegroundColor Yellow
}

$JarSrc = & (Join-Path $PSScriptRoot "resolve-app-jar.ps1")
$ExeSrc = Join-Path $Root "self-analyst-desktop/src-tauri/target/release/SelfAnalyst.exe"
if (-not (Test-Path -LiteralPath $JarSrc)) { throw "Missing jar: $JarSrc (run without -SkipBuild)" }
if (-not (Test-Path -LiteralPath $ExeSrc)) { throw "Missing exe: $ExeSrc (run without -SkipBuild)" }

# ── 3. Fresh dist-portable skeleton ────────────────────────────────────────────
Write-Host "=== 3/6 准备分发目录 $Dist ===" -ForegroundColor Cyan
Remove-Item -Recurse -Force -LiteralPath $Dist -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $Dist | Out-Null



Copy-Item -LiteralPath $ExeSrc -Destination (Join-Path $Dist "SelfAnalyst.exe") -Force
Copy-Item -LiteralPath $JarSrc -Destination (Join-Path $Dist "self-analyst-app.jar") -Force

# ── 4. jlink minimal JRE ───────────────────────────────────────────────────────
Write-Host "=== 4/6 Linking minimal JRE (jlink) ===" -ForegroundColor Cyan
$Runtime = Join-Path $Dist "runtime"
# Robust offline module set: java.se (incl. desktop/sql/xml/net.http) plus the
# jdk.* modules libraries load reflectively — TLS ECC, Unsafe, zip FS, locale
# data (Chinese formatting / i18n) and charsets.
$modules = "java.se,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.unsupported,jdk.zipfs,jdk.localedata,jdk.charsets,jdk.management.agent"
& (Join-Path $Jdk "bin\jlink.exe") `
    --add-modules $modules `
    --strip-debug --no-man-pages --no-header-files --compress=zip-6 `
    --output $Runtime
if ($LASTEXITCODE -ne 0) { throw "jlink failed" }

# ── 5. Prepare user config ─────────────────────────────────────────────────────
Write-Host "=== 5/6 使用首次运行配置 ===" -ForegroundColor Cyan

# WebView2 is intentionally not bundled — the app uses the system (Evergreen)
# WebView2 runtime, preinstalled on Windows 10/11.

# 用户配置和数据由所选运行目录在首次启动时生成；标准包不预置 portable.marker。

# ── 6. Package ─────────────────────────────────────────────────────────────────
Write-Host "=== 6/6 Packaging ===" -ForegroundColor Cyan

function Show-Sizes {
    $total = (Get-ChildItem -LiteralPath $Dist -Recurse -File | Measure-Object -Property Length -Sum).Sum
    Write-Host ("  dist-portable/  ({0:N1} MB)" -f ($total / 1MB))
    foreach ($d in @("runtime")) {
        $p = Join-Path $Dist $d
        if (Test-Path -LiteralPath $p) {
            $s = (Get-ChildItem -LiteralPath $p -Recurse -File | Measure-Object -Property Length -Sum).Sum
            Write-Host ("    {0,-22} {1,8:N1} MB" -f $d, ($s / 1MB))
        }
    }
}

function New-Zip([string]$name) {
    if ($NoZip) { return }
    New-Item -ItemType Directory -Force -Path $Artifacts | Out-Null
    $zip = Join-Path $Artifacts $name
    Remove-Item -Force -LiteralPath $zip -ErrorAction SilentlyContinue
    Write-Host "Zipping -> $zip ..." -ForegroundColor Cyan
    Compress-Archive -Path (Join-Path $Dist "*") -DestinationPath $zip -CompressionLevel Optimal
    $zsize = (Get-Item -LiteralPath $zip).Length / 1MB
    $hash = Get-FileHash -LiteralPath $zip -Algorithm SHA256
    [IO.File]::WriteAllText(
        $zip + ".sha256",
        "$($hash.Hash.ToLowerInvariant())  $name`n",
        [Text.UTF8Encoding]::new($false))
    Write-Host ("Created {0} ({1:N1} MB)" -f $zip, $zsize) -ForegroundColor Green
    Write-Host "SHA-256: $($hash.Hash.ToLowerInvariant())" -ForegroundColor DarkGray
}

Show-Sizes
New-Zip "SelfAnalyst-portable.zip"
Write-Host "运行：$(Join-Path $Dist 'SelfAnalyst.exe')" -ForegroundColor Green
