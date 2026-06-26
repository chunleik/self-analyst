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
$agentJs = Join-Path $AppUi "agent.js"
$styles = Join-Path $AppUi "styles.css"

# HTML checks
Assert-Contains $index 'id="behavior-advice-card"' "Missing behavior advice card container"

# JS checks
Assert-Contains $agentJs 'function\s+renderBehaviorAdvice\s*\(' "Missing renderBehaviorAdvice function"
Assert-Contains $agentJs 'escHtml' "renderBehaviorAdvice must use escHtml for text safety (SPEC-ADV-ERR-004)"

# CSS checks
Assert-Contains $styles '\.behavior-advice-card' "Missing behavior advice card styles"
Assert-Contains $styles 'type-encouragement' "Missing encouragement type styling (SPEC-ADV-UI-004)"
Assert-Contains $styles 'type-suggestion' "Missing suggestion type styling (SPEC-ADV-UI-004)"
Assert-Contains $styles 'type-reminder' "Missing reminder type styling (SPEC-ADV-UI-004)"
Assert-Contains $styles 'type-empty' "Missing empty type styling (SPEC-ADV-UI-004)"

Write-Output "desktop behavior advice static checks passed"
