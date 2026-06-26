# Build the accessibility sidecar (SPEC-AXS-060) and stage the binary into the
# content module resources, so Maven packages it onto the classpath at
# /axsidecar/<exe>. AxSidecarClient extracts it to a temp file at runtime.
param()

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)

Write-Host "=== Building accessibility sidecar (cargo release) ===" -ForegroundColor Cyan
Set-Location (Join-Path $Root "self-analyst-axsidecar")
cargo build --release --quiet
if ($LASTEXITCODE -ne 0) { throw "axsidecar cargo build failed" }
Set-Location $Root

$exe = if ($env:OS -eq "Windows_NT") { "axsidecar.exe" } else { "axsidecar" }
$src = Join-Path $Root "self-analyst-axsidecar/target/release/$exe"
if (-not (Test-Path -LiteralPath $src)) { throw "sidecar binary not found: $src" }

$destDir = Join-Path $Root "self-analyst-content/src/main/resources/axsidecar"
New-Item -ItemType Directory -Force -Path $destDir | Out-Null
Copy-Item -LiteralPath $src -Destination (Join-Path $destDir $exe) -Force
Write-Host "  staged -> self-analyst-content/src/main/resources/axsidecar/$exe" -ForegroundColor Green
