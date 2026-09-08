# 在可交互桌面中呈现正式托盘菜单，截图后点击菜单外部即可结束。
# 仅使用测试状态，不访问注册表、不启动 Java；需安装 Windows SDK 的 mt.exe。
param(
    [ValidateSet('enabled', 'unavailable', 'other')]
    [string]$Case = 'enabled'
)
$ErrorActionPreference = 'Stop'
$Root = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$Manifest = Join-Path $Root 'self-analyst-desktop/src-tauri/Cargo.toml'
$TestManifest = Join-Path $Root 'self-analyst-desktop/src-tauri/tests/native-menu.manifest'
$SdkBin = Join-Path ${env:ProgramFiles(x86)} 'Windows Kits/10/bin'
$ManifestTool = Get-ChildItem -LiteralPath $SdkBin -Directory |
    Sort-Object Name -Descending |
    ForEach-Object { Join-Path $_.FullName 'x64/mt.exe' } |
    Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } |
    Select-Object -First 1
if (-not $ManifestTool) { throw '缺少 Windows SDK mt.exe' }
$TestExecutable = $null
cargo test --manifest-path $Manifest --lib --features native-menu-review --no-run --message-format=json |
    ForEach-Object {
        $record = $_ | ConvertFrom-Json
        if ($record.reason -eq 'compiler-artifact' -and $record.profile.test -and $record.executable) {
            $TestExecutable = $record.executable
        }
    }
if ($LASTEXITCODE -ne 0 -or -not $TestExecutable) { throw '原生菜单测试构建失败' }
# tauri-build 为正式 EXE 嵌入清单，Rust 单元测试 EXE 需要单独补齐 v6 公共控件依赖。
& $ManifestTool -nologo -manifest $TestManifest "-outputresource:$TestExecutable;#1"
if ($LASTEXITCODE -ne 0) { throw '测试清单嵌入失败' }
$PreviousCase = $env:SELF_ANALYST_MENU_REVIEW_CASE
try {
    $env:SELF_ANALYST_MENU_REVIEW_CASE = $Case
    & $TestExecutable native_tray_menu_review --ignored --nocapture --test-threads=1
    if ($LASTEXITCODE -ne 0) { throw '原生菜单验收失败' }
} finally {
    if ($null -eq $PreviousCase) { Remove-Item Env:SELF_ANALYST_MENU_REVIEW_CASE -ErrorAction SilentlyContinue }
    else { $env:SELF_ANALYST_MENU_REVIEW_CASE = $PreviousCase }
}
