$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $PSScriptRoot
$AppUi = Join-Path $Root "self-analyst-app\src\main\resources\desktop-ui"

function Assert-Contains {
    param(
        [string]$Path,
        [string]$Pattern,
        [string]$Message
    )

    $text = Get-Content -LiteralPath $Path -Raw
    if ($text -notmatch $Pattern) {
        throw "$Message ($Path)"
    }
}

$i18n = Join-Path $AppUi "i18n.js"
$index = Join-Path $AppUi "index.html"
$state = Join-Path $AppUi "state.js"
$init = Join-Path $AppUi "init.js"
$chat = Join-Path $AppUi "chat.js"
$agent = Join-Path $AppUi "agent.js"

# Message catalog + lookup (SPEC-I18N-UI-001/002)
Assert-Contains $i18n 'var\s+MESSAGES\s*=' "i18n.js must declare a MESSAGES catalog"
Assert-Contains $i18n 'function\s+t\s*\(' "i18n.js must define the t() lookup function"
Assert-Contains $i18n 'function\s+applyI18n\s*\(' "i18n.js must define applyI18n()"

# index.html wiring (SPEC-I18N-UI-001/003a)
Assert-Contains $index '<script\s+src="i18n.js">' "index.html must load i18n.js"
Assert-Contains $index 'data-i18n' "index.html static text must carry data-i18n attributes"

# Language resolved before render (SPEC-I18N-RES-003/UI-004)
Assert-Contains $state 'lang\s*:' "state.js must default state.lang"
Assert-Contains $init 'state\.lang' "init.js must set state.lang before rendering"
Assert-Contains $init 'applyI18n' "init.js must call applyI18n before rendering"

# Runtime text goes through t() (SPEC-I18N-UI-003b)
Assert-Contains $chat 't\(' "chat.js runtime text must use t()"
Assert-Contains $agent 't\(' "agent.js runtime text must use t()"

# No stray CJK literals leaking outside the catalog in the converted modules.
$cjk = "[一-鿿]"
foreach ($f in @("agent.js", "chat.js", "chat-drawer.js", "config.js", "events.js", "ui.js", "utils.js")) {
    $p = Join-Path $AppUi $f
    $text = Get-Content -LiteralPath $p -Raw
    if ($text -match $cjk) {
        throw "Found hardcoded CJK literal in $f; all user-visible text must go through t() (SPEC-I18N-UI-003b)"
    }
}

Write-Output "desktop i18n static checks passed"
