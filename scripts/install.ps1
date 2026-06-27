# SelfAnalyst + ActivityWatch Installer (Windows PowerShell)
# Requires: PowerShell 5.1+
param()

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$InstallDir = "$env:USERPROFILE\.self-analyst"
$BinDir = "$InstallDir\bin"
$LibDir = "$InstallDir\lib"
$ConfigDir = "$InstallDir\config"
$ConfigFile = "$ConfigDir\application.properties"
$JarName = "self-analyst-app-1.0.0.jar"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectDir = Resolve-Path "$ScriptDir\.."

Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "  SelfAnalyst + ActivityWatch Installer" -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host ""

# ── 1. Check Java 21+ ──────────────────────────────────────────
Write-Host "[1/6] Checking Java 21+..." -ForegroundColor Yellow
$javaExe = $null
try {
    $javaVerOutput = java -version 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0) { throw "java not found" }
    if ($javaVerOutput -match 'version "(\d+)') {
        $javaMajor = [int]$Matches[1]
    } elseif ($javaVerOutput -match 'version "1\.(\d+)') {
        $javaMajor = [int]$Matches[1]
    } else {
        $javaMajor = 0
    }
    Write-Host "  Found Java version: $javaMajor"
    if ($javaMajor -lt 21) {
        Write-Host "ERROR: Java $javaMajor < 21. Please install JDK 21+ from https://adoptium.net/" -ForegroundColor Red
        exit 1
    }
    $javaExe = "java"
    Write-Host "  OK" -ForegroundColor Green
} catch {
    Write-Host "ERROR: Java not found. Install JDK 21+ from https://adoptium.net/" -ForegroundColor Red
    exit 1
}

# ── 2. Check Python 3 + pip ────────────────────────────────────
Write-Host "[2/6] Checking Python 3 + pip..." -ForegroundColor Yellow
$pythonExe = $null
foreach ($py in @("python3", "python")) {
    try {
        $pyVer = & $py --version 2>&1 | Out-String
        if ($pyVer -match 'Python 3\.(\d+)') {
            $pythonExe = $py
            $pyMinor = [int]$Matches[1]
            Write-Host "  Found $pyVer"
            break
        }
    } catch {}
}
if (-not $pythonExe) {
    Write-Host "ERROR: Python 3 not found. Install from https://python.org" -ForegroundColor Red
    exit 1
}
Write-Host "  OK" -ForegroundColor Green

# Ensure pip is available
$pipCheck = & $pythonExe -m pip --version 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Host "  pip not available, attempting to install..." -ForegroundColor Yellow
    & $pythonExe -m ensurepip --upgrade 2>&1 | Out-Null
}

# ── 3. Install ActivityWatch ────────────────────────────────────
Write-Host "[3/6] Installing ActivityWatch..." -ForegroundColor Yellow
$awCheck = & $pythonExe -c "import aw_core" 2>&1
if ($LASTEXITCODE -eq 0) {
    Write-Host "  ActivityWatch already installed."
} else {
    Write-Host "  Running: pip install activitywatch"
    $pipResult = & $pythonExe -m pip install --user activitywatch 2>&1
    if ($LASTEXITCODE -eq 0) {
        Write-Host "  ActivityWatch installed successfully." -ForegroundColor Green
    } else {
        Write-Host "  WARNING: pip install activitywatch failed." -ForegroundColor Yellow
        Write-Host "  SelfAnalyst will still work if ActivityWatch is running elsewhere."
        Write-Host "  Install manually: pip install activitywatch"
    }
}

# ── 4. Build SelfAnalyst ────────────────────────────────────────
Write-Host "[4/6] Building SelfAnalyst..." -ForegroundColor Yellow

$jarSource = $null
$targetJar = Join-Path $ProjectDir "self-analyst-app\target\$JarName"
$rootJar = Join-Path $ProjectDir $JarName

if (Test-Path $targetJar) {
    $jarSource = $targetJar
    Write-Host "  Using existing build: $targetJar"
} elseif (Test-Path $rootJar) {
    $jarSource = $rootJar
    Write-Host "  Using jar from project root: $rootJar"
} else {
    $mvnExe = $null
    foreach ($mvn in @("mvn", "mvnw")) {
        try {
            & $mvn --version 2>&1 | Out-Null
            if ($LASTEXITCODE -eq 0) {
                $mvnExe = $mvn
                break
            }
        } catch {}
    }
    if ($mvnExe) {
        Write-Host "  Running: $mvnExe package -DskipTests"
        Push-Location $ProjectDir
        try {
            & $mvnExe package -DskipTests -q 2>&1 | Out-Null
            if ($LASTEXITCODE -eq 0) {
                $jarSource = $targetJar
                Write-Host "  Build successful." -ForegroundColor Green
            } else {
                Write-Host "ERROR: Maven build failed." -ForegroundColor Red
                exit 1
            }
        } finally {
            Pop-Location
        }
    } else {
        Write-Host "ERROR: Maven not found and no pre-built jar available." -ForegroundColor Red
        Write-Host "  Install Maven: https://maven.apache.org/install.html"
        Write-Host "  Or run 'mvn package -DskipTests' in project root first."
        exit 1
    }
}

if (-not $jarSource -or -not (Test-Path $jarSource)) {
    Write-Host "ERROR: Could not find or build self-analyst jar." -ForegroundColor Red
    exit 1
}
$jarSize = "{0:N1} MB" -f ((Get-Item $jarSource).Length / 1MB)
Write-Host "  Jar: $jarSource ($jarSize)"

# ── 5. Create install directory ─────────────────────────────────
Write-Host "[5/6] Creating install directory..." -ForegroundColor Yellow
New-Item -ItemType Directory -Force -Path $BinDir | Out-Null
New-Item -ItemType Directory -Force -Path $LibDir | Out-Null
New-Item -ItemType Directory -Force -Path $ConfigDir | Out-Null
Write-Host "  Install dir: $InstallDir"

# Copy jar
Copy-Item $jarSource "$LibDir\$JarName" -Force
Write-Host "  Copied jar to $LibDir\$JarName"

# Generate config
if (Test-Path $ConfigFile) {
    Write-Host "  Config file already exists, skipping."
} else {
    Write-Host ""
    Write-Host "  ── LLM API Configuration ──" -ForegroundColor Cyan
    $apiKey = Read-Host "    API Key"
    $apiBaseUrl = Read-Host "    Base URL [https://api.openai.com/v1]"
    if (-not $apiBaseUrl) { $apiBaseUrl = "https://api.openai.com/v1" }
    $apiModel = Read-Host "    Model [gpt-4o]"
    if (-not $apiModel) { $apiModel = "gpt-4o" }

    $configContent = @"
# LLM configuration
llm.api-key=$apiKey
llm.base-url=$apiBaseUrl
llm.model=$apiModel

# ActivityWatch
aw.base-url=http://localhost:5600/api/0
aw.timeout=15000

# Memory
memory.dir=${env:USERPROFILE}\.self-analyst
"@
    Set-Content -Path $ConfigFile -Value $configContent -Encoding UTF8
    Write-Host "  Config saved." -ForegroundColor Green
}

# ── 6. Generate launcher scripts ─────────────────────────────────
Write-Host "[6/6] Generating launcher scripts..." -ForegroundColor Yellow

# Main launcher: self-analyst.ps1
$launcherContent = @'
param([string[]]$args)

$InstallDir = "$env:USERPROFILE\.self-analyst"
$Jar = "$InstallDir\lib\self-analyst-1.0.0.jar"
$AWUrl = "http://localhost:5600/api/0"

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

# Check if ActivityWatch is running; if not, start it
try {
    $null = Invoke-WebRequest -Uri "$AWUrl/info" -TimeoutSec 2 -UseBasicParsing
} catch {
    Write-Host "[SelfAnalyst] ActivityWatch not running, starting..." -ForegroundColor Yellow
    Start-Process -NoNewWindow -FilePath "aw-qt"
    # Wait for AW to be ready
    for ($i = 0; $i -lt 20; $i++) {
        try {
            $null = Invoke-WebRequest -Uri "$AWUrl/info" -TimeoutSec 2 -UseBasicParsing
            Write-Host "[SelfAnalyst] ActivityWatch connected." -ForegroundColor Green
            break
        } catch {
            Start-Sleep -Milliseconds 500
        }
    }
}

$jvmArgs = @(
    "-Dfile.encoding=UTF-8",
    "-Dsun.stdout.encoding=UTF-8",
    "-Dsun.stderr.encoding=UTF-8",
    "-jar", $Jar
)
$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = "java"
$psi.Arguments = ($jvmArgs + $args -join ' ')
$psi.UseShellExecute = $false
$proc = [System.Diagnostics.Process]::Start($psi)
$proc.WaitForExit()
exit $proc.ExitCode
'@
Set-Content -Path "$BinDir\self-analyst.ps1" -Value $launcherContent -Encoding UTF8

# AW starter
$awStartContent = @'
Write-Host "Starting ActivityWatch..." -ForegroundColor Cyan
Start-Process -NoNewWindow -FilePath "aw-qt"
Start-Sleep -Seconds 2
try {
    $null = Invoke-WebRequest -Uri "http://localhost:5600/api/0/info" -TimeoutSec 2 -UseBasicParsing
    Write-Host "ActivityWatch is running on http://localhost:5600" -ForegroundColor Green
} catch {
    Write-Host "WARNING: ActivityWatch may not have started. Check system tray." -ForegroundColor Yellow
}
'@
Set-Content -Path "$BinDir\start-aw.ps1" -Value $awStartContent -Encoding UTF8

# AW stopper
$awStopContent = @'
Write-Host "Stopping ActivityWatch..." -ForegroundColor Yellow
Get-Process -Name "aw-qt" -ErrorAction SilentlyContinue | Stop-Process -Force
Get-Process -Name "aw-server" -ErrorAction SilentlyContinue | Stop-Process -Force
Write-Host "ActivityWatch stopped." -ForegroundColor Green
'@
Set-Content -Path "$BinDir\stop-aw.ps1" -Value $awStopContent -Encoding UTF8

# Uninstaller
$uninstallContent = @'
param([switch]$KeepMemory)

$InstallDir = "$env:USERPROFILE\.self-analyst"
$Host.UI.RawUI.WindowTitle = "SelfAnalyst Uninstaller"

Write-Host "==========================================" -ForegroundColor Yellow
Write-Host "  SelfAnalyst Uninstaller" -ForegroundColor Yellow
Write-Host "==========================================" -ForegroundColor Yellow
Write-Host ""

# Stop processes
Write-Host "Stopping processes..." -ForegroundColor Yellow
Get-Process -Name "aw-qt" -ErrorAction SilentlyContinue | Stop-Process -Force
Get-Process -Name "aw-server" -ErrorAction SilentlyContinue | Stop-Process -Force
Write-Host "  Processes stopped."

# Ask about keeping memory
if (-not $KeepMemory) {
    $keep = Read-Host "Keep your memory data (goals, patterns, logs)? [Y/n]"
    if ($keep -eq "" -or $keep -eq "Y" -or $keep -eq "y") { $KeepMemory = $true }
}

if ($KeepMemory) {
    $memFile = "$InstallDir\memory.json"
    $backupFile = "$env:USERPROFILE\.self-analyst-memory-backup.json"
    if (Test-Path $memFile) {
        Copy-Item $memFile $backupFile -Force
        Write-Host "  Memory saved to $backupFile" -ForegroundColor Green
    }
}

# Remove install dir
Write-Host "Removing $InstallDir ..." -ForegroundColor Yellow
Remove-Item -Recurse -Force $InstallDir -ErrorAction SilentlyContinue

Write-Host ""
Write-Host "Uninstall complete." -ForegroundColor Green
Write-Host ""
Write-Host "Manual cleanup (if desired):"
Write-Host "  - Remove ~\.self-analyst-memory-backup.json if you don't need it"
Write-Host "  - Remove the PATH entry for ~\.self-analyst\bin if you added one"
Write-Host "  - ActivityWatch can be uninstalled with: pip uninstall activitywatch"
'@
Set-Content -Path "$BinDir\uninstall.ps1" -Value $uninstallContent -Encoding UTF8

Write-Host "  Launcher scripts generated." -ForegroundColor Green

# ── Done ─────────────────────────────────────────────────────────
Write-Host ""
Write-Host "==========================================" -ForegroundColor Green
Write-Host "  SelfAnalyst installation complete!" -ForegroundColor Green
Write-Host "==========================================" -ForegroundColor Green
Write-Host ""
Write-Host "  Install location: $InstallDir"
Write-Host "  Launcher:         $BinDir\self-analyst.ps1"
Write-Host ""
Write-Host "  To add to PATH (optional):" -ForegroundColor Cyan
Write-Host "    [Environment]::SetEnvironmentVariable('PATH', `$env:PATH + ';$BinDir', 'User')"
Write-Host ""
Write-Host "  Quick start:" -ForegroundColor Cyan
Write-Host "    & `"$BinDir\self-analyst.ps1`""
Write-Host ""
Write-Host "  Uninstall:" -ForegroundColor Cyan
Write-Host "    & `"$BinDir\uninstall.ps1`""
