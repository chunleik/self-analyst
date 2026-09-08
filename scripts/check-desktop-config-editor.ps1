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

$configJs = Join-Path $AppUi "config.js"
$apiJs = Join-Path $AppUi "api.js"
$eventsJs = Join-Path $AppUi "events.js"
$stateJs = Join-Path $AppUi "state.js"
$i18nJs = Join-Path $AppUi "i18n.js"
$styles = Join-Path $AppUi "styles.css"
$serverJava = Join-Path $Root "self-analyst-app\src\main\java\com\selfanalyst\desktop\DesktopServer.java"
$controllerJava = Join-Path $Root "self-analyst-app\src\main\java\com\selfanalyst\desktop\controller\DesktopConfigController.java"

# config.js: plain-text editor + helpers present, structured form removed (SPEC-CFGUI-UI-001)
Assert-Contains $configJs 'id="config-raw-editor"' "Missing raw config textarea"
Assert-Contains $configJs 'function\s+renderConfigTab' "Missing renderConfigTab function"
Assert-Contains $configJs 'escHtml' "renderConfigTab must escape text via escHtml"
Assert-Contains $configJs 'readLlmConfigFromEditor' "Missing readLlmConfigFromEditor helper"
Assert-Contains $configJs 'readEmbeddingConfigFromEditor' "Missing readEmbeddingConfigFromEditor helper"
Assert-NotContains $configJs 'renderCollectorFields' "Structured config residue: renderCollectorFields still present"

# TOML editor (SPEC-TOML-UI-001/003): TOML parser replaces the .properties parser
Assert-Contains $configJs 'function\s+parseEditorToml' "Missing parseEditorToml TOML parser"
Assert-NotContains $configJs 'parseEditorProps' "Properties residue: parseEditorProps still present"
Assert-Contains $configJs 'config\.toml' "Missing config.toml label/reference"

# The configuration file opens directly; the former insert/reference panel is gone.
Assert-Contains $configJs 'config-file-heading' "Missing config file path heading"
Assert-NotContains $configJs 'config-allkeys-btn' "All-keys panel should not precede the config file editor"
Assert-NotContains $configJs 'renderSupportedKeysPanel' "All-keys reference panel residue remains"
Assert-NotContains $configJs 'memory-manager|renderMemoryManager|bindMemoryManager' "Memory management must not be rendered in the config modal"
Assert-NotContains $styles '\.memory-manager' "Config memory manager styles residue remains"
Assert-NotContains $i18nJs 'memory\.(managerTitle|searchPlaceholder|status\.|enable|delete|confirmDelete)' "Config memory manager translations residue remains"

# The action bar is rendered before the raw editor, and connection results name the service.
$configText = Get-Content -LiteralPath $configJs -Raw
$actionBarCall = $configText.IndexOf('+ renderConfigActionBar()')
$rawEditorMarkup = $configText.IndexOf('<textarea id="config-raw-editor"')
if ($actionBarCall -lt 0 -or $rawEditorMarkup -lt 0 -or $actionBarCall -gt $rawEditorMarkup) {
    throw "Config action bar must render above the raw editor ($configJs)"
}
Assert-Contains $eventsJs 'connectOk", \{ service: "LLM" \}' "LLM success result must identify its service"
Assert-Contains $eventsJs 'connectOk", \{ service: "Embedding" \}' "Embedding success result must identify its service"
Assert-Contains $eventsJs 'connectFailed", \{ service: "LLM"' "LLM response failure must identify its service"
Assert-Contains $eventsJs 'connectFailed", \{ service: "Embedding"' "Embedding response failure must identify its service"
Assert-Contains $eventsJs 'testFailed", \{ service: "LLM"' "LLM request failure must identify its service"
Assert-Contains $eventsJs 'testFailed", \{ service: "Embedding"' "Embedding request failure must identify its service"
Assert-Contains $i18nJs 'config\.connectOk.*\{service\}.*\{service\}' "Success translations must include the service placeholder"
Assert-Contains $i18nJs 'config\.connectFailed.*\{service\}.*\{service\}' "Failure translations must include the service placeholder"
Assert-Contains $i18nJs 'config\.testFailed.*\{service\}.*\{service\}' "Test-error translations must include the service placeholder"
Assert-NotContains $styles '\.config-action-bar\s*\{[^}]*position:\s*(fixed|sticky)' "Config action bar must remain in normal document flow"

# api.js: raw client methods (SPEC-CFGUI-UI-002a/003d)
Assert-Contains $apiJs 'getRawConfig' "Missing api.getRawConfig"
Assert-Contains $apiJs 'saveRawConfig' "Missing api.saveRawConfig"
Assert-Contains $apiJs '/desktop/config/raw' "Missing /desktop/config/raw endpoint usage"

# Configuration history has been removed from the UI and API client.
Assert-NotContains $configJs 'config-history' "Config history UI residue remains"
Assert-NotContains $apiJs '/desktop/config/history' "Config history API residue remains"
Assert-NotContains $eventsJs 'config-history' "Config history event residue remains"
Assert-NotContains $stateJs 'configHistory' "Config history state residue remains"
Assert-NotContains $i18nJs 'config\.history' "Config history translation residue remains"
Assert-NotContains $serverJava '/desktop/config/history' "Config history route residue remains"
Assert-NotContains $controllerJava 'ConfigHistory|getConfigHistory|getConfigVersion|recordVersion' "Config history controller residue remains"

# styles.css: editor styles (SPEC-CFGUI-UI-001a)
Assert-Contains $styles '\.config-raw-editor' "Missing .config-raw-editor styles"
Assert-Contains $styles '\.config-file-heading' "Missing .config-file-heading styles"
Assert-NotContains $styles '\.config-history' "Config history styles residue remains"

Write-Output "desktop config editor static checks passed"
