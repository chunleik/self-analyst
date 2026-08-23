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
$storeJava = Join-Path $Root "self-analyst-app\src\main\java\com\selfanalyst\desktop\store\ChatSessionStore.java"
$sqliteJava = Join-Path $Root "self-analyst-app\src\main\java\com\selfanalyst\desktop\store\SqliteChatSessionIndex.java"
$deletionJava = Join-Path $Root "self-analyst-app\src\main\java\com\selfanalyst\desktop\store\ChatSessionDeletionCoordinator.java"
$serverJava = Join-Path $Root "self-analyst-app\src\main\java\com\selfanalyst\desktop\DesktopServer.java"

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

# Paged search runs against the full server index and ignores stale async responses.
Assert-Contains $apiJs 'params\.set\("q"' "api.js must send the server-side chat search query (SPEC-CSP-FE-005)"
Assert-Contains $chatJs 'scheduleChatSessionSearch' "chat.js missing debounced server search (SPEC-CSP-FE-005)"
Assert-Contains $chatJs 'chatSessionSearchRequestId' "chat.js must reject stale search responses (SPEC-CSP-FE-005)"
Assert-Contains $chatJs 'chatSessionLoadRequestId' "chat.js must reject stale startup/list responses (SPEC-CSP-FE-005)"
Assert-Contains $sqliteJava 'LOWER\(COALESCE\(title' "SQLite index missing metadata search (SPEC-CSP-FE-005)"
Assert-Contains $storeJava 'lastMessagePreview' "server search/index must retain lastMessagePreview (SPEC-CSP-FE-005)"
Assert-Contains $sqliteJava 'last_message_preview' "SQLite search must include preview (SPEC-CSP-FE-005)"
Assert-Contains $sqliteJava 'COALESCE\(summary' "SQLite search must include summary (SPEC-CSP-FE-005)"
Assert-Contains $storeJava 'index\.state' "ChatSessionStore missing recovery state (SPEC-CSP-API-010)"
Assert-Contains $storeJava 'listIndexPage' "ChatSessionStore missing cursor pagination (SPEC-CSP-API-001)"
Assert-Contains $chatJs 'loadMoreChatSessions' "chat.js missing paged load-more flow (SPEC-CSP-FE-002)"
Assert-Contains $storeJava 'index\.db\.ready' "ChatSessionStore missing monotonic SQLite migration marker (SPEC-CSP-DEC-016)"
Assert-Contains $sqliteJava 'ON CONFLICT\(id\) DO UPDATE' "SQLite projection must use row-level UPSERT (SPEC-CSP-DEC-016)"
Assert-Contains $sqliteJava 'LIMIT \?' "SQLite projection must bound paged queries (SPEC-CSP-DEC-016)"

# Cross-store deletion is journaled and production has a single writer.
Assert-Contains $storeJava 'delete-.*\.state' "ChatSessionStore missing durable delete tombstones (SPEC-CSP-DEC-017)"
Assert-Contains $deletionJava 'beginDeletion' "Deletion coordinator must commit intent before deleting stores (SPEC-CSP-DEC-017)"
Assert-Contains $deletionJava 'recoverPendingDeletions' "Deletion coordinator missing startup recovery (SPEC-CSP-DEC-017)"
Assert-Contains $serverJava 'openExclusive' "DesktopServer must acquire the chat writer lease (SPEC-CSP-DEC-018)"
Assert-Contains $serverJava 'chatSessionStore\.close' "DesktopServer must release the chat writer lease (SPEC-CSP-DEC-018)"

# init.js: legacy key removed and never read (SPEC-CSP-FE-006)
Assert-Contains $initJs 'localStorage\.removeItem\(CHAT_STORAGE_KEY' "init.js must remove the legacy localStorage key (SPEC-CSP-FE-006)"

Write-Output "desktop chat session store static checks passed"
