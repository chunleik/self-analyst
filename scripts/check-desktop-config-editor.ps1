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
$styles = Join-Path $AppUi "styles.css"

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

# Version history legacy-format badge + disabled switch (SPEC-TOML-VER-002)
Assert-Contains $configJs 'config-version-legacy' "Missing legacy-format history badge markup"

# api.js: raw client methods (SPEC-CFGUI-UI-002a/003d)
Assert-Contains $apiJs 'getRawConfig' "Missing api.getRawConfig"
Assert-Contains $apiJs 'saveRawConfig' "Missing api.saveRawConfig"
Assert-Contains $apiJs '/desktop/config/raw' "Missing /desktop/config/raw endpoint usage"

# Version history (SPEC-CFGUI-VER-UI)
Assert-Contains $configJs 'function\s+renderConfigHistory' "Missing renderConfigHistory function"
Assert-Contains $configJs 'function\s+switchToVersion' "Missing switchToVersion function"
Assert-Contains $configJs 'config-history-btn' "Missing history toggle button"
Assert-Contains $apiJs 'getConfigHistory' "Missing api.getConfigHistory"
Assert-Contains $apiJs '/desktop/config/history' "Missing /desktop/config/history endpoint usage"

# styles.css: editor styles (SPEC-CFGUI-UI-001a, SPEC-CFGUI-VER-UI, SPEC-TOML-VER-002)
Assert-Contains $styles '\.config-raw-editor' "Missing .config-raw-editor styles"
Assert-Contains $styles '\.config-history-panel' "Missing .config-history-panel styles"
Assert-Contains $styles '\.config-version-legacy' "Missing .config-version-legacy badge styles"

Write-Output "desktop config editor static checks passed"
