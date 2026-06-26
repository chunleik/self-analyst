$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $PSScriptRoot
$TauriConfig = Join-Path $Root "self-analyst-desktop\src-tauri\tauri.conf.json"
$TauriLib = Join-Path $Root "self-analyst-desktop\src-tauri\src\lib.rs"
$CargoToml = Join-Path $Root "self-analyst-desktop\src-tauri\Cargo.toml"
$AlreadyRunningText = "SelfAnalyst " + [string][char]0x5DF2 + [string][char]0x5728 + [string][char]0x8FD0 + [string][char]0x884C
$WebDesktopLabel = "Web" + [string][char]0x7248 + [string][char]0x684C + [string][char]0x9762
$OpenBrowserLabel = [string][char]0x6253 + [string][char]0x5F00 + [string][char]0x6D4F + [string][char]0x89C8 + [string][char]0x5668
$BackendServiceText = [string][char]0x540E + [string][char]0x7AEF + [string][char]0x670D + [string][char]0x52A1 + ": {}"

$config = Get-Content -LiteralPath $TauriConfig -Raw | ConvertFrom-Json
if ($config.app.PSObject.Properties.Name -contains "trayIcon") {
    throw "tauri.conf.json must not define app.trayIcon because the tray is created in Rust"
}

$lib = Get-Content -LiteralPath $TauriLib -Raw -Encoding UTF8
if ($lib -notmatch "(?s)\.icon\(\s*app\.default_window_icon\(\)\s*\.cloned\(\)") {
    throw "Rust tray builder must set the default window icon"
}

$cargo = Get-Content -LiteralPath $CargoToml -Raw
if ($cargo -notmatch 'windows-sys\s*=') {
    throw "Cargo.toml must declare windows-sys for the single-instance mutex"
}

if ($cargo -notmatch 'Win32_UI_WindowsAndMessaging') {
    throw "Cargo.toml must enable Win32_UI_WindowsAndMessaging for the already-running prompt"
}

if ($lib -notmatch 'SingleInstanceGuard') {
    throw "Rust app must hold a single-instance guard"
}

if ($lib -notmatch 'CreateMutexW') {
    throw "Rust app must acquire a named Windows mutex"
}

if ($lib -notmatch 'ERROR_ALREADY_EXISTS') {
    throw "Rust app must detect an already running instance"
}

if ($lib -notmatch 'MessageBoxW') {
    throw "Rust app must show a Windows message box when another instance exists"
}

if (-not $lib.Contains($AlreadyRunningText)) {
    throw "Already-running message must tell the user SelfAnalyst is already running"
}

$webDesktopMenuPattern = 'MenuItem::with_id\(app,\s*"web_desktop",\s*"' + [regex]::Escape($WebDesktopLabel) + '"'
if ($lib -notmatch $webDesktopMenuPattern) {
    throw "Tray menu must include Web desktop"
}

if ($lib -notmatch '"web_desktop"\s*=>\s*\{\s*let _ = open::that\(backend_url\(backend_port\(\),\s*"/desktop-ui/"\)\);') {
    throw "Web desktop tray menu must open the desktop web UI (/desktop-ui/)"
}

if ($lib.Contains($OpenBrowserLabel)) {
    throw "Tray menu must not use the vague open-browser label"
}

if ($lib -notmatch '"about"\s*=>\s*\{\s*show_about_message\(\);') {
    throw "About tray menu must show native about content"
}

if ($lib -notmatch 'SelfAnalyst v' -or $lib -notmatch 'CARGO_PKG_VERSION') {
    throw "About content must include the SelfAnalyst version"
}

if (-not $lib.Contains($BackendServiceText)) {
    throw "About content must include backend service URL"
}

if ($lib.Contains('w.eval("alert(''SelfAnalyst v1.0.0'')")')) {
    throw "About tray menu must not depend on the webview alert"
}

if ($lib -notmatch 'acquire_single_instance\(\)\s*\{\s*Some\(guard\)\s*=>\s*guard,\s*None\s*=>\s*return,') {
    throw "Rust app must return before creating tray/window when another instance exists"
}

Write-Output "desktop tray static checks passed"
