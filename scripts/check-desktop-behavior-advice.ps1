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

# 看板仅展示时间轴；后端建议契约由服务端测试覆盖。
Assert-Contains $index 'id="timeline-body"' "缺少时间轴容器"
Assert-Contains $agentJs 'function\s+renderTimeline\s*\(' "缺少时间轴渲染函数"
Assert-Contains $agentJs 'escHtml' "时间轴文本必须安全转义"
Assert-Contains $styles '\.dashboard-layout' "缺少看板单栏布局"
foreach ($path in @($index, $agentJs, $styles)) {
    if ((Get-Content -LiteralPath $path -Raw) -match 'behavior-advice-card|future-tasks-panel|renderBehaviorAdvice') {
        throw "看板中仍包含已移除的建议或任务展示 ($path)"
    }
}

Write-Output "看板时间轴静态检查通过"
