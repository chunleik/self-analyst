# Desktop UI Light Theme Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Convert `desktop-ui` from the current dark theme to the approved A1 Airy Blue light theme without changing DOM structure, JavaScript behavior, or backend APIs.

**Architecture:** This is a CSS-only theme migration centered on `self-analyst-app/src/main/resources/desktop-ui/styles.css`. The implementation replaces global design tokens first, then tightens component-specific rules where the old dark theme encoded implicit assumptions such as white translucent panels, dark input spinner filters, or undefined fallback variables.

**Tech Stack:** Vanilla HTML/CSS/ES5 static resources served by Javalin from classpath and loaded by the Tauri WebView at `/desktop-ui/`; Maven for packaging verification.

---

## File Map

- Modify: `self-analyst-app/src/main/resources/desktop-ui/styles.css`
  - Owns all desktop-ui theme variables, layout shell, cards, forms, task list, chat, config modal, drawer, overlays, responsive rules.
- Do not modify: `self-analyst-app/src/main/resources/desktop-ui/index.html`
  - Existing DOM IDs and script order stay unchanged.
- Do not modify: `self-analyst-app/src/main/resources/desktop-ui/*.js`
  - Existing API calls, tab switching, chat, config, i18n, and state logic stay unchanged.

## Task 1: Replace Global Theme Tokens And Base Shell

**Files:**
- Modify: `self-analyst-app/src/main/resources/desktop-ui/styles.css:5-201`

- [ ] **Step 1: Capture current baseline diff**

Run:

```powershell
git diff -- self-analyst-app/src/main/resources/desktop-ui/styles.css
```

Expected: no unrelated CSS changes before starting this task.

- [ ] **Step 2: Replace the `:root` block with A1 Airy Blue tokens**

Replace lines 6-53 with:

```css
:root {
  --bg-primary: #fbfcff;
  --bg-secondary: #ffffff;
  --bg-card: #ffffff;
  --bg-card-hover: #f5f8fd;
  --bg-input: #f7faff;
  --bg-overlay: rgba(15, 23, 42, 0.28);
  --bg-surface: #f8fbff;
  --bg-hover: #eef4fb;

  --border-color: #e7edf6;
  --border-light: #d8e3f2;

  --text-primary: #172033;
  --text-secondary: #526071;
  --text-muted: #7b8798;
  --text-inverse: #ffffff;

  --accent: #5b8def;
  --accent-hover: #477ee6;
  --accent-soft: #f2f7ff;
  --accent-dim: rgba(91, 141, 239, 0.14);

  --green: #168a5b;
  --green-dim: rgba(22, 138, 91, 0.12);
  --orange: #c97914;
  --orange-dim: rgba(201, 121, 20, 0.13);
  --red: #d14343;
  --red-dim: rgba(209, 67, 67, 0.12);
  --gray: #6f7a89;
  --gray-dim: rgba(111, 122, 137, 0.13);

  --radius-sm: 6px;
  --radius-md: 8px;
  --radius-lg: 12px;

  --shadow-sm: 0 1px 2px rgba(15, 23, 42, 0.04);
  --shadow-md: 0 10px 24px rgba(15, 23, 42, 0.08);
  --shadow-lg: 0 24px 60px rgba(15, 23, 42, 0.14);

  --font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "Microsoft YaHei",
    "PingFang SC", "Hiragino Sans GB", "Noto Sans CJK SC", sans-serif;
  --font-mono: "Cascadia Code", "Fira Code", "JetBrains Mono", Consolas, monospace;

  --transition-fast: 150ms ease;
  --transition-normal: 250ms ease;
  --transition-slow: 400ms cubic-bezier(0.4, 0, 0.2, 1);

  --nav-height: 48px;
  --chat-width: 420px;
}
```

- [ ] **Step 3: Tighten base background and scrollbar styling**

Update the base and scrollbar rules to keep the light shell consistent:

```css
body {
  font-family: var(--font-family);
  font-size: 14px;
  line-height: 1.5;
  color: var(--text-primary);
  background: var(--bg-primary);
  -webkit-font-smoothing: antialiased;
  -moz-osx-font-smoothing: grayscale;
}

::-webkit-scrollbar-thumb {
  background: #d5deec;
  border-radius: 3px;
}
::-webkit-scrollbar-thumb:hover {
  background: var(--border-light);
}
```

- [ ] **Step 4: Update button and top navigation rules**

Revise existing `.btn`, `.btn-primary`, `.btn-outline`, `.btn-icon`, and `#top-nav` rules in lines 109-201 to this light-theme behavior:

```css
.btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 14px;
  font-family: inherit;
  font-size: 13px;
  font-weight: 500;
  line-height: 1.4;
  border: 1px solid transparent;
  border-radius: var(--radius-sm);
  cursor: pointer;
  background: transparent;
  color: var(--text-primary);
  transition: background var(--transition-fast), border-color var(--transition-fast),
    color var(--transition-fast), box-shadow var(--transition-fast), transform var(--transition-fast);
  white-space: nowrap;
  user-select: none;
}
.btn:hover {
  opacity: 1;
}
.btn:focus-visible {
  outline: none;
  box-shadow: 0 0 0 3px var(--accent-dim);
}
.btn:active {
  transform: scale(0.98);
}
.btn:disabled {
  opacity: 0.48;
  cursor: not-allowed;
  transform: none;
}

.btn-primary {
  background: var(--accent);
  color: #fff;
  border-color: var(--accent);
  box-shadow: 0 4px 10px rgba(91, 141, 239, 0.22);
}
.btn-primary:hover {
  background: var(--accent-hover);
  border-color: var(--accent-hover);
}

.btn-outline {
  background: #fff;
  border-color: var(--border-color);
  color: var(--text-secondary);
}
.btn-outline:hover {
  background: var(--bg-card-hover);
  color: var(--text-primary);
  border-color: var(--border-light);
}

.btn-icon {
  padding: 6px;
  border: 1px solid transparent;
  background: transparent;
  color: var(--text-secondary);
  cursor: pointer;
  border-radius: var(--radius-sm);
}
.btn-icon:hover {
  background: var(--bg-card-hover);
  color: var(--text-primary);
  border-color: var(--border-color);
}

#top-nav {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: var(--nav-height);
  padding: 0 16px;
  background: rgba(255, 255, 255, 0.92);
  border-bottom: 1px solid var(--border-color);
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04);
  user-select: none;
  -webkit-app-region: drag;
  flex-shrink: 0;
}
```

- [ ] **Step 5: Commit token and shell changes**

Run:

```powershell
git add -- self-analyst-app/src/main/resources/desktop-ui/styles.css
git commit -m "style: 切换 desktop-ui 浅色主题变量" -m "Co-Authored-By: Codex <codex@openai.com>"
```

Expected: one commit containing only `styles.css`.

## Task 2: Update Shared Panels, Forms, Tags, And Config Modal

**Files:**
- Modify: `self-analyst-app/src/main/resources/desktop-ui/styles.css:318-1323`

- [ ] **Step 1: Add light card boundaries**

Update `.card` and `.config-section` to use borders instead of dark-theme shadow dependency:

```css
.card {
  background: var(--bg-card);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-sm);
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.config-section {
  background: var(--bg-card);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-sm);
  overflow: hidden;
}
```

- [ ] **Step 2: Soften task creation and task item states**

Update task-related rules so they match A1 without high-contrast dark assumptions:

```css
.task-new-form {
  padding: 12px;
  margin-bottom: 12px;
  background: var(--bg-card);
  border-radius: var(--radius-md);
  border: 1px solid rgba(91, 141, 239, 0.42);
  box-shadow: 0 0 0 3px rgba(91, 141, 239, 0.06);
}

.task-item {
  padding: 10px 12px;
  background: var(--bg-card);
  border-radius: var(--radius-sm);
  border: 1px solid var(--border-color);
  cursor: pointer;
  transition: background var(--transition-fast), border-color var(--transition-fast),
    box-shadow var(--transition-fast);
}
.task-item:hover {
  background: var(--bg-card-hover);
  border-color: var(--border-light);
}
.task-item.expanded {
  border-color: rgba(91, 141, 239, 0.62);
  box-shadow: 0 0 0 3px rgba(91, 141, 239, 0.08);
}
```

- [ ] **Step 3: Remove dark number spinner treatment**

Replace the dark spinner comment and rule at lines 924-929 with:

```css
.config-field input[type="number"]::-webkit-inner-spin-button,
.config-field input[type="number"]::-webkit-outer-spin-button {
  opacity: 0.75;
}
```

- [ ] **Step 4: Make config action and modal panels feel light**

Update these existing blocks:

```css
.config-action-bar {
  position: fixed;
  left: 16px;
  right: 16px;
  bottom: 16px;
  z-index: 5;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 12px 14px;
  background: rgba(255, 255, 255, 0.94);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-sm);
  box-shadow: var(--shadow-md);
}

.config-modal-panel {
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -47%);
  width: min(760px, 92vw);
  max-height: 86vh;
  background: var(--bg-secondary);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-lg);
  display: flex;
  flex-direction: column;
  opacity: 0;
  transition: opacity var(--transition-normal), transform var(--transition-normal);
}
```

- [ ] **Step 5: Normalize config raw editor and history panels**

Update raw editor, history, preview, and all-keys panel backgrounds to use light tokens:

```css
.config-raw-editor {
  display: block;
  width: 100%;
  box-sizing: border-box;
  min-height: 420px;
  padding: 12px 14px;
  font-family: ui-monospace, "Cascadia Code", Consolas, "Courier New", monospace;
  font-size: 13px;
  line-height: 1.5;
  white-space: pre;
  tab-size: 4;
  resize: vertical;
  color: var(--text-primary);
  background: #fbfdff;
  border: 1px solid var(--border-color);
  border-radius: 8px;
  outline: none;
}

.config-history-panel,
.config-allkeys-panel {
  margin-bottom: 10px;
  overflow-y: auto;
  border: 1px solid var(--border-color);
  border-radius: 8px;
  background: var(--bg-surface);
}

.config-history-panel {
  max-height: 260px;
}

.config-allkeys-panel {
  max-height: 300px;
}

.config-history-preview {
  margin: 0;
  padding: 10px 12px;
  max-height: 200px;
  overflow: auto;
  font-family: ui-monospace, Consolas, monospace;
  font-size: 12px;
  line-height: 1.45;
  white-space: pre;
  background: #fbfdff;
  border-top: 1px solid var(--border-color);
  color: var(--text-primary);
}
```

- [ ] **Step 6: Commit shared panel and config changes**

Run:

```powershell
git add -- self-analyst-app/src/main/resources/desktop-ui/styles.css
git commit -m "style: 调整浅色主题组件层次" -m "Co-Authored-By: Codex <codex@openai.com>"
```

Expected: one commit containing only `styles.css`.

## Task 3: Update Chat, Drawer, Overlay, And Residual Dark Assumptions

**Files:**
- Modify: `self-analyst-app/src/main/resources/desktop-ui/styles.css:1325-1914`

- [ ] **Step 1: Update drawer and chat panel surfaces**

Apply light background rules to drawer, sidebars, workspace, and context panels:

```css
.chat-panel {
  position: absolute;
  right: 0;
  top: var(--nav-height);
  bottom: 0;
  width: var(--chat-width);
  max-width: 100vw;
  background: var(--bg-secondary);
  border-left: 1px solid var(--border-color);
  box-shadow: var(--shadow-lg);
  display: flex;
  flex-direction: column;
  transform: translateX(100%);
  transition: transform var(--transition-slow);
}

.chat-tab-layout {
  display: grid;
  grid-template-columns: 260px minmax(400px, 1fr) minmax(280px, 360px);
  gap: 0;
  height: 100%;
  background: var(--bg-primary);
}

.chat-session-sidebar,
.chat-context-panel {
  background: var(--bg-secondary);
}

.chat-workspace {
  display: flex;
  flex-direction: column;
  background: var(--bg-primary);
  min-width: 0;
}
```

- [ ] **Step 2: Replace the dark translucent structured response item**

Replace `.structured-response-item` with:

```css
.structured-response-item {
  padding: 8px 10px;
  background: var(--bg-surface);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-sm);
}
```

- [ ] **Step 3: Fix undefined fallback variables in chat delete hover**

Replace `.chat-session-item .session-delete-btn:hover` with:

```css
.chat-session-item .session-delete-btn:hover {
  color: var(--red);
  background: var(--red-dim);
}
```

- [ ] **Step 4: Add light borders to chat context sections**

Update `.chat-context-section`:

```css
.chat-context-section {
  background: var(--bg-card);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-sm);
  padding: 10px;
}
```

- [ ] **Step 5: Ensure overlay and loading states stay readable**

Keep the overlay full-screen but make the content a light card:

```css
.overlay {
  position: fixed;
  inset: 0;
  z-index: 200;
  background: var(--bg-primary);
  display: flex;
  align-items: center;
  justify-content: center;
}

.overlay-content {
  text-align: center;
  max-width: 400px;
  padding: 32px;
  background: var(--bg-card);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-md);
}
```

- [ ] **Step 6: Search for residual dark-theme literals**

Run:

```powershell
Select-String -LiteralPath 'self-analyst-app\src\main\resources\desktop-ui\styles.css' -Pattern '#1e1e1e|#252525|#2d2d2d|#333333|#3c3c3c|rgba\(255, 255, 255, 0\.03\)|invert\(0\.7\)|--color-danger|--bg-hover'
```

Expected: no matches except `--bg-hover` in the `:root` token definition.

- [ ] **Step 7: Commit chat and residual polish changes**

Run:

```powershell
git add -- self-analyst-app/src/main/resources/desktop-ui/styles.css
git commit -m "style: 完成聊天与弹层浅色适配" -m "Co-Authored-By: Codex <codex@openai.com>"
```

Expected: one commit containing only `styles.css`.

## Task 4: Verify Theme Syntax, Packaging, And Visual Surfaces

**Files:**
- Verify: `self-analyst-app/src/main/resources/desktop-ui/styles.css`
- Verify: `self-analyst-app/src/main/resources/desktop-ui/index.html`

- [ ] **Step 1: Run CSS sanity checks**

Run:

```powershell
$css = Get-Content -LiteralPath 'self-analyst-app\src\main\resources\desktop-ui\styles.css' -Raw
$open = ([regex]::Matches($css, '\{')).Count
$close = ([regex]::Matches($css, '\}')).Count
if ($open -ne $close) { throw "CSS brace mismatch: $open opens, $close closes" }
Select-String -LiteralPath 'self-analyst-app\src\main\resources\desktop-ui\styles.css' -Pattern '#1e1e1e|#252525|#2d2d2d|rgba\(255, 255, 255, 0\.03\)|invert\(0\.7\)' | ForEach-Object { throw "Unexpected CSS marker: $($_.Line)" }
"CSS sanity OK"
```

Expected output: `CSS sanity OK`.

- [ ] **Step 2: Verify Maven packaging can include the static resource**

Run:

```powershell
mvn -pl self-analyst-app -am -DskipTests package
```

Expected: Maven exits with code 0 and includes `styles.css` in the app jar resources.

- [ ] **Step 3: Start or reuse the desktop UI server for visual verification**

If a backend is already running on port 5700, open:

```text
http://localhost:5700/desktop-ui/
```

If it is not running, use the generated static preview fallback by opening:

```text
D:\aicode\self-analyst\self-analyst-app\src\main\resources\desktop-ui\index.html
```

Expected: browser renders the top navigation, Agent tab shell, Chat tab shell, config modal shell when opened through the running app, and no dark background remains.

- [ ] **Step 4: Inspect responsive layout**

Check desktop width around `1200x800` and a narrow width below `800px`.

Expected:
- Top navigation text and status items do not overlap.
- Agent layout stacks correctly below `800px`.
- Chat layout collapses to a single column below `1100px`.
- Buttons and labels remain readable on the A1 light theme.

- [ ] **Step 5: Final commit if verification required CSS corrections**

Only if Step 1-4 required additional CSS edits, run:

```powershell
git add -- self-analyst-app/src/main/resources/desktop-ui/styles.css
git commit -m "style: 修正浅色主题验证问题" -m "Co-Authored-By: Codex <codex@openai.com>"
```

Expected: no commit is created if verification passed without further edits.
