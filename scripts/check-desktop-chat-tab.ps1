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

$index = Join-Path $AppUi "index.html"
$stateJs = Join-Path $AppUi "state.js"
$chatJs = Join-Path $AppUi "chat.js"
$uiJs = Join-Path $AppUi "ui.js"
$styles = Join-Path $AppUi "styles.css"

Assert-Contains $index 'data-tab="chat"' "Missing chat tab navigation"
Assert-Contains $index 'id="tab-chat"' "Missing chat tab section"
Assert-Contains $index 'id="chat-session-list"' "Missing chat session list"
Assert-Contains $index 'id="new-chat-session-btn"' "Missing new chat session button"
Assert-Contains $index 'id="chat-session-search-input"' "Missing chat search input"
Assert-Contains $index 'id="chat-thread"' "Missing chat thread"
Assert-Contains $index 'id="chat-tab-input"' "Missing chat tab input"
Assert-Contains $index 'id="chat-tab-send-btn"' "Missing chat tab send button"
Assert-Contains $index 'id="chat-context-summary"' "Missing chat context summary"
Assert-Contains $index 'id="chat-recent-activity"' "Missing chat recent activity"
Assert-Contains $index 'id="chat-task-suggestions"' "Missing chat task suggestions"
Assert-Contains $index 'id="chat-context-toggles"' "Missing chat context toggles"

Assert-Contains $stateJs 'chatSessions:\s*\[\]' "State does not include chatSessions"
Assert-Contains $stateJs 'activeChatSessionId:\s*null' "State does not include activeChatSessionId"
Assert-Contains $stateJs 'chatContextToggles:\s*\{' "State does not include chatContextToggles"

Assert-Contains $stateJs 'selfAnalyst\.chatSessions\.v1' "Missing chat localStorage key"
Assert-Contains $chatJs 'function\s+renderChatTab\s*\(' "Missing renderChatTab"
Assert-Contains $chatJs 'function\s+createChatSession\s*\(' "Missing createChatSession"
Assert-Contains $chatJs 'function\s+sendChatTabMessage\s*\(' "Missing sendChatTabMessage"
Assert-Contains $chatJs 'function\s+buildChatContext\s*\(' "Missing buildChatContext"
Assert-Contains $chatJs 'function\s+openChatTabWithContext\s*\(' "Missing openChatTabWithContext"
Assert-Contains $chatJs 'function\s+deleteChatSession\s*\(' "Missing deleteChatSession"

Assert-Contains $uiJs 'tab\s*===\s*"chat"' "switchTab does not support chat"

Assert-Contains $styles '\.chat-tab-layout' "Missing chat tab layout styles"
Assert-Contains $styles '\.chat-session-sidebar' "Missing chat session sidebar styles"
Assert-Contains $styles '\.chat-workspace' "Missing chat workspace styles"
Assert-Contains $styles '\.chat-context-panel' "Missing chat context panel styles"
Assert-Contains $styles '\.chat-tab-message' "Missing chat tab message styles"
Assert-Contains $styles '@media\s*\(max-width:\s*1100px\)' "Missing narrow chat tab media query"

# Verify all 10 script tags in correct load order
$indexContent = Get-Content -LiteralPath $index -Raw
$scripts = @(
    "utils.js",
    "state.js",
    "api.js",
    "agent.js",
    "chat.js",
    "chat-drawer.js",
    "config.js",
    "ui.js",
    "events.js",
    "init.js"
)
for ($i = 0; $i -lt $scripts.Count; $i++) {
    $pattern = 'src="' + [regex]::Escape($scripts[$i]) + '"'
    if ($indexContent -notmatch $pattern) {
        throw "Missing or misordered script tag: $($scripts[$i]) (expected position $i)"
    }
    # Ensure it comes after all previous scripts
    $pos = $indexContent.IndexOf($scripts[$i])
    for ($j = 0; $j -lt $i; $j++) {
        $prevPos = $indexContent.IndexOf($scripts[$j])
        if ($prevPos -gt $pos) {
            throw "Script load order violation: $($scripts[$j]) must come before $($scripts[$i])"
        }
    }
}
Write-Output "All 10 script tags verified in correct load order"

Write-Output "desktop chat tab static checks passed"
