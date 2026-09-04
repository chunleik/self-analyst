# SelfAnalyst dist build script.

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $Root

$Dist = Join-Path $Root "dist"

function Remove-LegacyToolDirectory {
    param([Parameter(Mandatory = $true)][string]$Name)

    $toolsRoot = [System.IO.Path]::GetFullPath((Join-Path $Dist "tools")).TrimEnd('\')
    $target = Join-Path $toolsRoot $Name
    if (-not (Test-Path -LiteralPath $target)) { return }

    $resolved = [System.IO.Path]::GetFullPath((Resolve-Path -LiteralPath $target).Path)
    if (-not $resolved.StartsWith($toolsRoot + '\', [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to remove legacy tool outside dist/tools: $resolved"
    }
    Remove-Item -LiteralPath $resolved -Recurse -Force
    Write-Host "  Removed legacy tool directory: $Name" -ForegroundColor DarkGray
}
function Stop-SelfAnalystProcesses {
    Write-Host "=== 1/5 Stopping running SelfAnalyst processes ===" -ForegroundColor Cyan

    $exePath = Join-Path $Dist "SelfAnalyst.exe"
    try {
        Get-Process -Name "SelfAnalyst" -ErrorAction SilentlyContinue |
            Where-Object {
                try {
                    $_.Path -and ([System.IO.Path]::GetFullPath($_.Path)).Equals(
                        [System.IO.Path]::GetFullPath($exePath),
                        [System.StringComparison]::OrdinalIgnoreCase)
                } catch {
                    $false
                }
            } |
            ForEach-Object {
                Write-Host "  Stopping SelfAnalyst.exe (PID $($_.Id))"
                Stop-Process -Id $_.Id -Force -ErrorAction SilentlyContinue
            }
    } catch {
        Write-Warning "Could not inspect SelfAnalyst.exe processes: $($_.Exception.Message)"
    }

    try {
        $rootPattern = [regex]::Escape([System.IO.Path]::GetFullPath($Root))
        $jarPattern = [regex]::Escape("self-analyst-app.jar")
        Get-CimInstance Win32_Process -Filter "Name = 'java.exe' OR Name = 'javaw.exe'" -ErrorAction Stop |
            Where-Object {
                $_.CommandLine -and
                $_.CommandLine -match $jarPattern -and
                $_.CommandLine -match $rootPattern
            } |
            ForEach-Object {
                Write-Host "  Stopping Java backend (PID $($_.ProcessId))"
                Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
            }
    } catch {
        Write-Warning "Could not inspect Java backend processes: $($_.Exception.Message)"
    }

    Start-Sleep -Milliseconds 500
}

Stop-SelfAnalystProcesses

Write-Host "=== 2/5 Building Java jar ===" -ForegroundColor Cyan
# Stage the accessibility sidecar into content resources before packaging (SPEC-AXS-060)
& (Join-Path $Root "scripts/build-axsidecar.ps1")
mvn package -DskipTests -q
if ($LASTEXITCODE -ne 0) { throw "Maven build failed" }

Write-Host "=== 3/5 Building Tauri exe ===" -ForegroundColor Cyan
Set-Location self-analyst-desktop/src-tauri
cargo build --release --quiet
if ($LASTEXITCODE -ne 0) { throw "Cargo build failed" }
Set-Location $Root

Write-Host "=== 4/5 Updating dist/ (preserving data) ===" -ForegroundColor Cyan
New-Item -ItemType Directory -Force -Path $Dist | Out-Null
Remove-LegacyToolDirectory -Name "PaddleOCR-json"
Remove-LegacyToolDirectory -Name "whisper"

Copy-Item -LiteralPath (Join-Path $Root "self-analyst-app/target/self-analyst-app-1.0.0.jar") `
    -Destination (Join-Path $Dist "self-analyst-app.jar") -Force

Copy-Item -LiteralPath (Join-Path $Root "self-analyst-desktop/src-tauri/target/release/SelfAnalyst.exe") `
    -Destination (Join-Path $Dist "SelfAnalyst.exe") -Force

Write-Host "=== 5/5 Done ===" -ForegroundColor Green
Get-ChildItem $Dist -Recurse | Measure-Object -Property Length -Sum | ForEach-Object {
    $sizeMB = [math]::Round($_.Sum / 1MB, 1)
    Write-Host "dist/ ($sizeMB MB)"
}
Write-Host "Run: .\dist\SelfAnalyst.exe"
