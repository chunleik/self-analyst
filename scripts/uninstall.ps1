# SelfAnalyst + ActivityWatch Uninstaller (Windows PowerShell)
param([switch]$KeepMemory)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$InstallDir = "$env:USERPROFILE\.self-analyst"

Write-Host "==========================================" -ForegroundColor Yellow
Write-Host "  SelfAnalyst Uninstaller" -ForegroundColor Yellow
Write-Host "==========================================" -ForegroundColor Yellow
Write-Host ""

if (-not (Test-Path $InstallDir)) {
    Write-Host "Install directory not found: $InstallDir" -ForegroundColor Red
    Write-Host "Nothing to uninstall."
    exit 0
}

# ── Stop processes ─────────────────────────────────────────────
Write-Host "Stopping processes..." -ForegroundColor Yellow
Get-Process -Name "java" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -like "*self-analyst*" } |
    Stop-Process -Force
Get-Process -Name "aw-qt" -ErrorAction SilentlyContinue | Stop-Process -Force
Get-Process -Name "aw-server" -ErrorAction SilentlyContinue | Stop-Process -Force
Write-Host "  Processes stopped."

# ── Keep memory? ───────────────────────────────────────────────
if (-not $KeepMemory) {
    $keep = Read-Host "Keep your memory data (goals, patterns, logs)? [Y/n]"
    if ($keep -eq "" -or $keep -eq "Y" -or $keep -eq "y") {
        $KeepMemory = $true
    }
}

if ($KeepMemory) {
    $memFile = "$InstallDir\memory.json"
    $backupFile = "$env:USERPROFILE\.self-analyst-memory-backup.json"
    if (Test-Path $memFile) {
        Copy-Item $memFile $backupFile -Force
        Write-Host "  Memory saved to $backupFile" -ForegroundColor Green
    } else {
        Write-Host "  No memory.json found to save."
    }
}

# ── Remove install dir ─────────────────────────────────────────
Write-Host ""
Write-Host "Removing $InstallDir ..." -ForegroundColor Yellow
Remove-Item -Recurse -Force $InstallDir -ErrorAction SilentlyContinue

Write-Host ""
Write-Host "==========================================" -ForegroundColor Green
Write-Host "  Uninstall complete" -ForegroundColor Green
Write-Host "==========================================" -ForegroundColor Green
Write-Host ""
Write-Host "Manual cleanup (if desired):"
Write-Host "  - Remove ~\.self-analyst-memory-backup.json if you don't need it"
Write-Host "  - Remove the PATH entry for ~\.self-analyst\bin in system environment variables"
Write-Host "  - ActivityWatch can be uninstalled with: pip uninstall activitywatch"
