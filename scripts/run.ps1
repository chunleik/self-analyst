# SelfAnalyst 开发启动脚本
# 用法:
#   .\scripts\run.ps1           # 直接用已有 jar 启动
#   .\scripts\run.ps1 -Build    # 先构建再启动
#   .\scripts\run.ps1 -Build -SkipTests  # 跳过测试构建后启动
param(
    [switch]$Build,
    [switch]$SkipTests
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$ScriptDir  = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectDir = Resolve-Path "$ScriptDir\.."

Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "  SelfAnalyst Dev Launcher" -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan

# ── 构建 ──────────────────────────────────────────────────────────
if ($Build) {
    Write-Host "[BUILD] 构建中..." -ForegroundColor Yellow
    $mvnArgs = @("package")
    if ($SkipTests) { $mvnArgs += "-DskipTests" }
    Push-Location $ProjectDir
    try {
        & mvn @mvnArgs
        if ($LASTEXITCODE -ne 0) {
            Write-Host "[ERROR] Maven 构建失败" -ForegroundColor Red
            exit 1
        }
    } finally {
        Pop-Location
    }
    Write-Host "[BUILD] 构建完成" -ForegroundColor Green
}

# ── 检查 jar ──────────────────────────────────────────────────────
try {
    $JarPath = & (Join-Path $ScriptDir "resolve-app-jar.ps1")
} catch {
    Write-Host "[ERROR] $_" -ForegroundColor Red
    Write-Host "  请先运行: .\scripts\run.ps1 -Build" -ForegroundColor Yellow
    exit 1
}

$jarSize = "{0:N1} MB" -f ((Get-Item $JarPath).Length / 1MB)
$jarTime = (Get-Item $JarPath).LastWriteTime.ToString("yyyy-MM-dd HH:mm:ss")
Write-Host ""
Write-Host "  Jar : $JarPath" -ForegroundColor Gray
Write-Host "  大小: $jarSize  |  构建时间: $jarTime" -ForegroundColor Gray
Write-Host "  端口: http://localhost:5700  (AW + DesktopServer)" -ForegroundColor Gray
Write-Host ""
Write-Host "[START] 启动中，按 Ctrl+C 退出..." -ForegroundColor Green
Write-Host ""

# ── 日志目录（从 application.properties 读取 log.dir）────────────
$PropsFile = "$ProjectDir\self-analyst-app\src\main\resources\application.properties"
$logDirProp = (Select-String -Path $PropsFile -Pattern '^log\.dir\s*=(.+)$').Matches[0].Groups[1].Value.Trim()
$LogDir = $logDirProp -replace '^\.[\\/]', "$ProjectDir\" -replace '/', '\'
if (-not (Test-Path $LogDir)) { New-Item -ItemType Directory -Force $LogDir | Out-Null }
$LogFile = "$LogDir\run.log"
Write-Host "  日志: $LogFile" -ForegroundColor Gray
Write-Host ""

# ── 启动 ──────────────────────────────────────────────────────────
$javaArgs = @(
    "-Dfile.encoding=UTF-8",
    "-Dsun.stdout.encoding=UTF-8",
    "-Dsun.stderr.encoding=UTF-8",
    "-jar", $JarPath
)
Push-Location $ProjectDir
try {
    & java @javaArgs
} finally {
    Pop-Location
}

exit $LASTEXITCODE
