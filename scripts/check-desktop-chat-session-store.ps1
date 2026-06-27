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

function Assert-NotContains {
    param(
        [string]$Path,
        [string]$Pattern,
        [string]$Message
    )

    $text = Get-Content -LiteralPath $Path -Raw
    if ($text -match $Pattern) {
        throw "$Message ($Path)"
    }
}

$apiJs = Join-Path $AppUi "api.js"
$chatJs = Join-Path $AppUi "chat.js"
$initJs = Join-Path $AppUi "init.js"

# api.js: 8 session/message REST client methods (SPEC-CSP-FE-001)
Assert-Contains $apiJs 'listSessions\s*:'    "api.js missing listSessions (SPEC-CSP-FE-001)"
Assert-Contains $apiJs 'getSession\s*:'      "api.js missing getSession (SPEC-CSP-FE-001)"
Assert-Contains $apiJs 'createSession\s*:'   "api.js missing createSession (SPEC-CSP-FE-001)"
Assert-Contains $apiJs 'updateSession\s*:'   "api.js missing updateSession (SPEC-CSP-FE-001)"
Assert-Contains $apiJs 'deleteSession\s*:'   "api.js missing deleteSession (SPEC-CSP-FE-001)"
Assert-Contains $apiJs 'appendMessages\s*:'  "api.js missing appendMessages (SPEC-CSP-FE-001)"
Assert-Contains $apiJs 'updateMessage\s*:'   "api.js missing updateMessage (SPEC-CSP-FE-001)"
Assert-Contains $apiJs 'setActiveSession\s*:' "api.js missing setActiveSession (SPEC-CSP-FE-001)"

# chat.js: routes writes through REST, no localStorage source-of-truth
Assert-Contains $chatJs 'api\.listSessions'  "chat.js must load the session index via api.listSessions (SPEC-CSP-FE-002)"
Assert-Contains $chatJs 'api\.appendMessages' "chat.js must persist messages via api.appendMessages (SPEC-CSP-FE-004)"
Assert-NotContains $chatJs 'localStorage\.setItem\(CHAT_STORAGE_KEY' "chat.js must not write sessions to localStorage (SPEC-CSP-DEC-002)"

# chat.js: search matches index fields, not message bodies (SPEC-CSP-FE-005)
Assert-Contains $chatJs 'lastMessagePreview' "chat.js search must reference lastMessagePreview (SPEC-CSP-FE-005)"
Assert-Contains $chatJs 's\.summary'         "chat.js search must reference summary (SPEC-CSP-FE-005)"

# init.js: legacy key removed and never read (SPEC-CSP-FE-006)
Assert-Contains $initJs 'localStorage\.removeItem\(CHAT_STORAGE_KEY' "init.js must remove the legacy localStorage key (SPEC-CSP-FE-006)"

Write-Output "desktop chat session store static checks passed"
