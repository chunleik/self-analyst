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
#     tools/PaddleOCR-json/      optional OCR engine (only with -WithOcr)
#     tools/whisper/             whisper.cpp + ggml-small.bin (audio, off by default)
#     data/                      created on first run (aw-data, memory, ...)
#
# Prereqs: JDK 21 (jlink), Maven, Rust/cargo, and any selected optional tools.

param(
    [switch]$SkipBuild,   # reuse existing jar/exe instead of rebuilding
    [switch]$NoZip,       # leave the folder, don't produce the .zip
    [switch]$WithOcr,     # include the optional PaddleOCR pack
    # Which package(s) to emit. The heavy work (mvn/cargo/jlink) runs
    # once; the variants differ only by whether the whisper audio model is bundled.
    #   both    -> SelfAnalyst-portable-minimal.zip (no audio) + SelfAnalyst-portable.zip (full)
    #   minimal -> minimal only;  full -> full only
    [ValidateSet('both', 'full', 'minimal')]
    [string]$Variant = 'both'
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $Root

$Dist = Join-Path $Root "dist-portable"
$Artifacts = Join-Path $Root "artifacts"

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

$JarSrc = Join-Path $Root "self-analyst-app/target/self-analyst-app-1.0.0.jar"
$ExeSrc = Join-Path $Root "self-analyst-desktop/src-tauri/target/release/self-analyst-desktop.exe"
if (-not (Test-Path -LiteralPath $JarSrc)) { throw "Missing jar: $JarSrc (run without -SkipBuild)" }
if (-not (Test-Path -LiteralPath $ExeSrc)) { throw "Missing exe: $ExeSrc (run without -SkipBuild)" }

# ── 3. Fresh dist-portable skeleton ────────────────────────────────────────────
Write-Host "=== 3/6 Preparing dist-portable/ ===" -ForegroundColor Cyan
Remove-Item -Recurse -Force -LiteralPath $Dist -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $Dist | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $Dist "data/memory") | Out-Null

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

# ── 5. Stage tools ─────────────────────────────────────────────────────────────
Write-Host "=== 5/6 Staging tools ===" -ForegroundColor Cyan
$DistTools = Join-Path $Dist "tools"
New-Item -ItemType Directory -Force -Path $DistTools | Out-Null

$paddleSrc = Join-Path $Root "tools/PaddleOCR-json"
if ($WithOcr) {
    if (-not (Test-Path -LiteralPath $paddleSrc)) {
        throw "Missing tools/PaddleOCR-json. Run scripts/download-tools.ps1 -WithOcr first."
    }
    Copy-Item -Recurse -LiteralPath $paddleSrc -Destination $DistTools
    Write-Host "  Optional PaddleOCR-json staged"
} else {
    Write-Host "  Optional PaddleOCR-json omitted (default)" -ForegroundColor DarkGray
}

# whisper is staged later (step 6) only for the full variant, so the minimal
# zip can be produced from the same dist without re-running the heavy build.

# WebView2 is intentionally not bundled — the app uses the system (Evergreen)
# WebView2 runtime, preinstalled on Windows 10/11.

# Seed an empty user config so first launch finds the file (LLM key set via UI).
$seedCfg = Join-Path $Dist "data/memory/config.properties"
if (-not (Test-Path -LiteralPath $seedCfg)) {
    @(
        "# SelfAnalyst user config (overrides bundled application.properties).",
        "# Set your LLM API key here or via the desktop 'Config' tab.",
        "#llm.api-key=",
        "#llm.base-url=https://api.openai.com/v1",
        "#llm.model=gpt-4o"
    ) | Set-Content -Path $seedCfg -Encoding UTF8
}

# ── 6. Package variant(s): minimal (no whisper) and/or full (with whisper) ──────
Write-Host "=== 6/6 Packaging ($Variant) ===" -ForegroundColor Cyan

$whisperSrc  = Join-Path $Root "tools/whisper"
$distWhisper = Join-Path $DistTools "whisper"
$hasWhisper  = Test-Path -LiteralPath $whisperSrc

$wantMinimal = $Variant -in @('both', 'minimal')
$wantFull    = $Variant -in @('both', 'full')
$OcrSuffix   = if ($WithOcr) { "-ocr" } else { "" }
if ($wantFull -and -not $hasWhisper) {
    Write-Warning "tools/whisper missing — cannot build the full (audio) variant."
    Write-Warning "Run scripts/download-tools.ps1 (without -SkipWhisper) first."
    $wantFull = $false
    if (-not $wantMinimal) { $wantMinimal = $true }  # still emit something usable
}

function Show-Sizes {
    $total = (Get-ChildItem -LiteralPath $Dist -Recurse -File | Measure-Object -Property Length -Sum).Sum
    Write-Host ("  dist-portable/  ({0:N1} MB)" -f ($total / 1MB))
    foreach ($d in @("runtime", "tools/PaddleOCR-json", "tools/whisper")) {
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
    Write-Host ("Created {0} ({1:N1} MB)" -f $zip, $zsize) -ForegroundColor Green
}

# Start clean so the minimal package never contains whisper.
Remove-Item -Recurse -Force -LiteralPath $distWhisper -ErrorAction SilentlyContinue

# Minimal first (no whisper present yet), then add whisper once and do full.
if ($wantMinimal) {
    Write-Host "--- minimal variant (no audio model) ---" -ForegroundColor Green
    Show-Sizes
    New-Zip "SelfAnalyst-portable-minimal$OcrSuffix.zip"
}
if ($wantFull) {
    Copy-Item -Recurse -LiteralPath $whisperSrc -Destination $DistTools
    Write-Host "--- full variant (with whisper) ---" -ForegroundColor Green
    Show-Sizes
    New-Zip "SelfAnalyst-portable$OcrSuffix.zip"
}

if (Test-Path -LiteralPath $distWhisper) {
    Write-Host "dist-portable/ left as: full (with whisper)" -ForegroundColor DarkGray
} else {
    Write-Host "dist-portable/ left as: minimal (no whisper)" -ForegroundColor DarkGray
}
Write-Host "Run: .\dist-portable\SelfAnalyst.exe" -ForegroundColor Green
