# Download external tools (PaddleOCR + whisper.cpp)
param()

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $Root

# 定位 7-Zip：优先 PATH 中的 7z，其次常见安装路径
function Resolve-SevenZip {
    $cmd = Get-Command 7z -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    foreach ($p in @("$env:ProgramFiles\7-Zip\7z.exe", "${env:ProgramFiles(x86)}\7-Zip\7z.exe")) {
        if (Test-Path -LiteralPath $p) { return $p }
    }
    throw "未找到 7-Zip。请安装 7-Zip 并确保 7z 在 PATH 中（https://www.7-zip.org/）。"
}
$SevenZip = Resolve-SevenZip

Write-Host "=== Downloading PaddleOCR-json ===" -ForegroundColor Cyan
$paddleUrl = "https://github.com/hiroi-sora/PaddleOCR-json/releases/download/v1.4.1/PaddleOCR-json_v1.4.1_windows_x64.7z"
$paddleZip = "$env:TEMP\paddleocr.7z"

Invoke-WebRequest -Uri $paddleUrl -OutFile $paddleZip
New-Item -ItemType Directory -Force -Path tools/PaddleOCR-json | Out-Null
& $SevenZip x $paddleZip -o"$Root\tools\PaddleOCR-json" -y | Out-Null
Remove-Item $paddleZip

Write-Host "=== Downloading whisper.cpp ===" -ForegroundColor Cyan
$whisperUrl = "https://github.com/ggml-org/whisper.cpp/releases/download/v1.8.6/whisper-bin-x64.zip"
$whisperZip = "$env:TEMP\whisper-bin.zip"

Invoke-WebRequest -Uri $whisperUrl -OutFile $whisperZip
New-Item -ItemType Directory -Force -Path tools/whisper | Out-Null
Expand-Archive -Path $whisperZip -DestinationPath tools/whisper -Force
Remove-Item $whisperZip

Write-Host "=== Downloading whisper model (ggml-small.bin, 466MB) ===" -ForegroundColor Cyan
$modelUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin"
Invoke-WebRequest -Uri $modelUrl -OutFile tools/whisper/ggml-small.bin

Write-Host "Done. tools/ ready." -ForegroundColor Green
