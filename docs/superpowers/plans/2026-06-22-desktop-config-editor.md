# Desktop Config Text Editor — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把桌面端「配置」模态从结构化分区表单改为纯文本编辑器，用户直接编辑 `config.properties` 原始文本；新增 raw GET/PUT 端点，保留旧结构化端点不破坏现有集成测试。**后续追加**：为每次保存保留最近 10 个版本历史（自动命名、查看/切换，Task 13），并修复评审三处问题（密钥外发 / UTF-8 编码 / Web 仪表盘端口，Task 14）。

**Architecture:** 新增 `GET/PUT /desktop/config/raw` 两个端点，落在既有 `DesktopConfigController`（共用其 `UserConfigStore`）。`UserConfigStore` 增加 verbatim 读写（`readRaw`/`saveRaw`，UTF-8 原子写）。控制器的校验/差异/模板逻辑全部抽成**纯静态/包级方法**（不依赖 Javalin `Context`），便于单测（沿用 `DesktopAgentControllerTest` 的纯方法测试风格）。前端 `config.js` 的 `renderConfigTab()` 改为渲染单个 `<textarea>` 编辑器 + 连接测试工具条 + 既有操作栏；脏态由「编辑器文本 vs 基准文本」逐字符比较得出。

**Tech Stack:** Java 21（records、`Properties.load(Reader)`、`Files.move` 原子 rename）/ Javalin / Jackson / vanilla ES5 JS / CSS。无新依赖。

**Spec:** `docs/specs/desktop-config-editor.md` — all `SPEC-CFGUI-*` requirements

---

### File Map

| File | Action | Purpose |
|------|--------|---------|
| `self-analyst-app/src/main/java/com/selfanalyst/desktop/store/UserConfigStore.java` | Modify | 新增 `readRaw()` / `saveRaw(String)`（UTF-8 verbatim 原子写） |
| `self-analyst-app/src/main/java/com/selfanalyst/desktop/controller/DesktopConfigController.java` | Modify | 新增 raw 端点 + 纯静态助手（解析校验、restart/unknown 差异、模板、`buildRawResponse`/`applyRawSave`） |
| `self-analyst-app/src/main/java/com/selfanalyst/desktop/DesktopServer.java` | Modify | 注册 `GET/PUT /desktop/config/raw` 路由；旧路由保留 |
| `self-analyst-app/src/main/resources/desktop-ui/api.js` | Modify | 新增 `getRawConfig()` / `saveRawConfig(text)` |
| `self-analyst-app/src/main/resources/desktop-ui/state.js` | Modify | 新增 `configRawText` / `configRawBaseline`；脏态字段复用 |
| `self-analyst-app/src/main/resources/desktop-ui/ui.js` | Modify | `loadConfig()` 改用 raw 接口；`closeConfigModal()` 脏态二次确认 |
| `self-analyst-app/src/main/resources/desktop-ui/config.js` | Modify | `renderConfigTab()` 渲染纯文本编辑器；脏态/保存/放弃/测试值解析重写 |
| `self-analyst-app/src/main/resources/desktop-ui/index.html` | Modify | 配置模态主体替换为编辑器容器 + 工具条 |
| `self-analyst-app/src/main/resources/desktop-ui/events.js` | Modify | 测试按钮从编辑器取值；关闭脏态确认 |
| `self-analyst-app/src/main/resources/desktop-ui/styles.css` | Modify | `.config-raw-editor` / `.config-editor-toolbar` 样式 |
| `self-analyst-app/src/test/java/com/selfanalyst/desktop/controller/DesktopConfigControllerTest.java` | Create | 纯助手单测（解析校验/restart/unknown/模板/applyRawSave/buildRawResponse） |
| `self-analyst-app/src/test/java/com/selfanalyst/desktop/store/UserConfigStoreRawTest.java` | Create | `readRaw`/`saveRaw` round-trip（`@TempDir`） |
| `scripts/check-desktop-config-editor.ps1` | Create | 前端静态校验（textarea / renderConfigTab / api / css / 历史面板） |
| `self-analyst-app/src/main/java/com/selfanalyst/desktop/store/ConfigHistoryStore.java` | Create | 版本快照存储（`config-history.json`，add/list/get/updateSummary，保留最近 10）— Task 13 |
| `self-analyst-app/src/test/java/com/selfanalyst/desktop/store/ConfigHistoryStoreTest.java` | Create | 版本存储与保留单测 — Task 13 |
| `self-analyst-app/src/main/java/com/selfanalyst/config/Config.java` | Modify | 用户配置改 UTF-8 `Reader` 读取（评审 P2）— Task 14 |

---

### Task 1: UserConfigStore — verbatim raw 读写

**Files:** Modify `UserConfigStore.java`

实现要点：
- `public String readRaw() throws IOException`：文件不存在返回 `""`；存在时用 **UTF-8** 读取全文（`Files.readString(filePath, StandardCharsets.UTF_8)`），**原样**返回（保留注释、空行、键顺序）。
- `public void saveRaw(String text) throws IOException`：`Files.createDirectories(parent)` → 写入 `{name}.tmp`（UTF-8，`Files.writeString`）→ `Files.move(tmp, filePath, REPLACE_EXISTING)`（原子 rename，与既有 `save()` 一致）。**不**经分区重排/转义/`Properties.store`，整文本逐字节落盘。
- 读写统一 UTF-8，保证 `readRaw(saveRaw(x)) == x` 的 round-trip 保真（含中文值）。这是有意决策：既有 `loadUser()` 走 ISO-8859-1 `InputStream` 仅用于结构化合并，raw 路径独立用 UTF-8，不动旧方法。

- [x] **Step 1: 加 `readRaw()`**
  在 `UserConfigStore.java` 新增 `public String readRaw() throws IOException`：`if (!Files.exists(filePath)) return "";` 否则 `return Files.readString(filePath, StandardCharsets.UTF_8);`（原样返回，不裁剪/不转义）。import `java.nio.charset.StandardCharsets`。`// SPEC-CFGUI-API-001a`

- [x] **Step 2: 加 `saveRaw(String)`**（依赖：无）
  `public void saveRaw(String text) throws IOException`：`Files.createDirectories(filePath.getParent())` → `Path tmp = filePath.getParent().resolve(filePath.getFileName() + ".tmp")` → `Files.writeString(tmp, text, StandardCharsets.UTF_8)` → `Files.move(tmp, filePath, StandardCopyOption.REPLACE_EXISTING)`。**不**调用既有 `save()`/`Properties.store`，整文本逐字节落盘。`// SPEC-CFGUI-API-002b, SPEC-CFGUI-GOAL-003, SPEC-CFGUI-NON-003`

- [ ] **Step 3: 提交**（依赖：Step 1–2）— 未执行（用户未要求提交）
  `git commit -m "feat: UserConfigStore 增加 verbatim raw 读写（UTF-8 原子写）\n\nSPEC-CFGUI-API-002b"`

**验证**：编译通过 + 由 Task 10 `UserConfigStoreRawTest` 覆盖（`readRaw` 缺文件返回 `""`；`saveRaw`→`readRaw` 逐字符 round-trip，对应 SPEC-CFGUI-TST-003）。

---

### Task 2: DesktopConfigController — raw 端点 + 纯助手

**Files:** Modify `DesktopConfigController.java`

设计：把所有逻辑放进**可单测的纯方法**，端点方法只做 `ctx.body()` 解析与 `ctx.json()`/状态码映射。

定义单一数据源（受支持键 → 默认值），供模板与 unknownKeys 共用：
```java
// 26 项受支持键（对齐 SPEC-CFG-TOOL-002），dotted key → 默认值
private static final Map<String, String> SUPPORTED_DEFAULTS = new LinkedHashMap<>() {{
    put("llm.api-key", ""); put("llm.base-url", "https://api.openai.com/v1");
    put("llm.model", "gpt-4o"); put("llm.temperature", "0.7");
    put("websearch.enabled", "true"); put("websearch.mcp-url", "https://search.parallel.ai/mcp");
    put("websearch.api-key", "");
    put("agent.summaryRefreshMinutes", "5"); put("agent.allowAgentTasks", "false");
    put("agent.cacheSummaries", "true");
    put("desktop.hideToTray", "true"); put("desktop.autoOpenWindow", "true");
    put("desktop.autoStartBackend", "true");
    put("aw.mode", "embedded"); put("aw.port", "5700");
    put("aw.collection.window", "true"); put("aw.collection.afk", "true");
    put("aw.collection.content", "true"); put("aw.ocr.engine", "auto");
    put("aw.audio.enabled", "false"); put("aw.audio.whisperPath", "tools/whisper");
    put("embedding.enabled", "true"); put("embedding.base-url", "https://api.openai.com/v1");
    put("embedding.api-key", ""); put("embedding.model", "text-embedding-3-small");
    put("embedding.dimensions", "1536"); put("embedding.send-encoding-format", "true");
}};
```
> `RESTART_REQUIRED` 沿用类内已有常量（对齐 `SPEC-CFG-TOOL-003`，DEC-004），不重新定义。

纯助手（包级/静态，供测试直调）：
- `static Properties parseProperties(String text) throws IOException`：`Properties p = new Properties(); p.load(new StringReader(text)); return p;`。`StringReader` 让校验与编码无关；非法 `\u` 转义会抛 `IllegalArgumentException`/`IOException`。
- `static List<String> computeRestartRequired(Properties oldP, Properties newP)`：遍历 `union(old.keys, new.keys)`，值发生新增/删除/变更且 `RESTART_REQUIRED.contains(key)` 的，收集（去重、稳定顺序）。
- `static List<String> computeUnknownKeys(Properties newP)`：`newP` 中不在 `SUPPORTED_DEFAULTS.keySet()` 的键。
- `static String buildTemplate()`：生成带注释模板——头注释 + 按分区分组、每行 `# <key>=<default>`（全部注释掉），供用户取消注释填写。DEC-001 的可发现性补偿。

端点 + 端点级纯方法（用 `userStore`，可用 `@TempDir` 构造的 store 单测）：
- `record RawConfigResponse(String text, String path, boolean exists)`
- `RawConfigResponse buildRawResponse()`：`raw = userStore.readRaw()`；若 `raw.isBlank()` → `text=buildTemplate(), exists=false`；否则 `text=raw, exists=true`。`path=userStore.filePath().toString()`。**text 不脱敏**（DEC-002）。
- `record RawSaveResult(List<String> restartRequired, List<String> unknownKeys)`
- `RawSaveResult applyRawSave(String text) throws IOException`：`Properties newP = parseProperties(text)`（抛出即校验失败）；`Properties oldP = userStore.loadUser()`；算 restart/unknown；`userStore.saveRaw(text)`；返回结果。**先校验后落盘**，校验失败不写文件（API-002a/d）。
- `public void getRawConfig(Context ctx)`：`ctx.json(buildRawResponse())`。
- `public void putRawConfig(Context ctx)`：`Map body = MAPPER.readValue(ctx.body(), Map.class)`；`String text = String.valueOf(body.get("text"))`；try `applyRawSave` → `ctx.json({saved:true, restartRequired, unknownKeys})`；解析校验失败 → `ctx.status(400)` + `{error:...}`；其他异常 → 500（沿用既有 `escapeJson` 错误体）。

- [x] **Step 1: 加 `SUPPORTED_DEFAULTS` 常量**
  在 `DesktopConfigController.java` 加上文所列 26 项 `LinkedHashMap<String,String>`（dotted key → 默认值）。作为模板与 unknownKeys 的单一数据源。`// SPEC-CFGUI-NON-001, SPEC-CFGUI-DEC-004`

- [x] **Step 2: 加 `parseProperties`**（依赖：无）
  `static Properties parseProperties(String text) throws IOException`：`Properties p = new Properties(); p.load(new StringReader(text)); return p;`。import `java.io.StringReader`。非法 `\u` 转义会抛异常 → 即校验失败信号。`// SPEC-CFGUI-API-002a`

- [x] **Step 3: 加 `computeRestartRequired` / `computeUnknownKeys`**（依赖：Step 1）
  `static List<String> computeRestartRequired(Properties oldP, Properties newP)`：遍历 `old∪new` 键，值新增/删除/变更且 `RESTART_REQUIRED.contains(key)` 收集（去重、稳定顺序）。`static List<String> computeUnknownKeys(Properties newP)`：`newP` 中不在 `SUPPORTED_DEFAULTS.keySet()` 的键。`// SPEC-CFGUI-API-002c, SPEC-CFGUI-GOAL-004`

- [x] **Step 4: 加 `buildTemplate()`**（依赖：Step 1）
  `static String buildTemplate()`：头注释 + 按分区分组遍历 `SUPPORTED_DEFAULTS`，每行输出注释形式 `# <key>=<default>`（全部注释掉）。`// SPEC-CFGUI-API-001b, SPEC-CFGUI-DEC-001`

- [x] **Step 5: 加 `RawConfigResponse` + `buildRawResponse()`**（依赖：Step 4 + Task 1.Step 1）
  `record RawConfigResponse(String text, String path, boolean exists)`。`RawConfigResponse buildRawResponse() throws IOException`：`raw = userStore.readRaw()`；`raw.isBlank()` → `text=buildTemplate(), exists=false`；否则 `text=raw, exists=true`；`path=userStore.filePath().toString()`。**text 不脱敏**。`// SPEC-CFGUI-API-001a, SPEC-CFGUI-DEC-002, SPEC-CFGUI-NON-004`

- [x] **Step 6: 加 `RawSaveResult` + `applyRawSave()`**（依赖：Step 2,3 + Task 1.Step 2）
  `record RawSaveResult(List<String> restartRequired, List<String> unknownKeys)`。`RawSaveResult applyRawSave(String text) throws IOException`：`Properties newP = parseProperties(text)`（抛即校验失败、**不落盘**）→ `Properties oldP = userStore.loadUser()` → 算 restart/unknown → `userStore.saveRaw(text)` → 返回结果。`// SPEC-CFGUI-API-002a, SPEC-CFGUI-API-002b, SPEC-CFGUI-API-002c, SPEC-CFGUI-GOAL-006`

- [x] **Step 7: 加 `getRawConfig(Context)` 端点**（依赖：Step 5）
  `public void getRawConfig(Context ctx)`：`try { ctx.json(buildRawResponse()); } catch(IOException e){ ctx.status(500)... }`。`// SPEC-CFGUI-API-001a`

- [x] **Step 8: 加 `putRawConfig(Context)` 端点**（依赖：Step 6）
  解析 `MAPPER.readValue(ctx.body(), Map.class)` 取 `text`；`try` 调 `applyRawSave` → `ctx.json(Map.of("saved",true,"restartRequired",r.restartRequired(),"unknownKeys",r.unknownKeys()))`；解析校验异常（`IOException`/`IllegalArgumentException`）→ `ctx.status(400).json({error:...})`（不落盘）；其它 `Throwable` → 500（沿用既有 `escapeJson` 错误体）。`// SPEC-CFGUI-API-002a, SPEC-CFGUI-API-002c, SPEC-CFGUI-API-002d`

- [ ] **Step 9: 提交**（依赖：Step 1–8）— 未执行（用户未要求提交）
  `git commit -m "feat: 配置 raw GET/PUT 端点与纯助手（解析校验/重启键/未知键/模板）\n\nSPEC-CFGUI-API-001, SPEC-CFGUI-API-002, SPEC-CFGUI-DEC-001, SPEC-CFGUI-DEC-002"`

**验证**：编译通过 + 由 Task 10 `DesktopConfigControllerTest` 覆盖 SPEC-CFGUI-TST-001/002/004/005/006（`buildRawResponse`/`applyRawSave`/`parseProperties` 纯方法直测）。

---

### Task 3: DesktopServer — 注册 raw 路由

**Files:** Modify `DesktopServer.java`

- 在 `// ── Config tab ──` 区块、既有 `/desktop/config` 路由旁追加：
```java
app.get("/desktop/config/raw", configCtrl::getRawConfig);
app.put("/desktop/config/raw", configCtrl::putRawConfig);
```
- 既有 `GET/PUT /desktop/config`、`test-llm`、`test-embedding` 路由**保留不动**（API-003a/b）。

- [x] **Step 1: 注册 raw 路由**（依赖：Task 2.Step 7,8）
  在 `DesktopServer.java` `// ── Config tab ──` 区块、`app.put("/desktop/config", ...)` 之后追加：
  ```java
  app.get("/desktop/config/raw", configCtrl::getRawConfig);
  app.put("/desktop/config/raw", configCtrl::putRawConfig);
  ```
  `// SPEC-CFGUI-API-001`

- [x] **Step 2: 确认旧路由不动**
  代码审查：`GET/PUT /desktop/config`、`test-llm`、`test-embedding` 四条路由原样保留。`// SPEC-CFGUI-API-003a, SPEC-CFGUI-API-003b`

- [ ] **Step 3: 提交**（依赖：Step 1）— 未执行（用户未要求提交）
  `git commit -m "feat: 注册 /desktop/config/raw GET/PUT 路由\n\nSPEC-CFGUI-API-001, SPEC-CFGUI-API-003"`

**验证**：`mvn -pl self-analyst-app compile` 通过；旧端点路由保留（Task 12 全量 `mvn test` 含 `configRoundTrip` 不回归）。

---

### Task 4: 前端 api.js — raw 客户端方法

**Files:** Modify `api.js`

- `getRawConfig: function () { return fetch(API_BASE + "/desktop/config/raw").then(...json) }`
- `saveRawConfig: function (text) { fetch PUT /desktop/config/raw, body JSON.stringify({text: text}) }`，沿用 `saveConfig` 的 `r.text()→try JSON.parse→错误抛出` 容错模式（返回 `{saved, restartRequired, unknownKeys}` 或抛 `error`）。
- 旧 `getConfig`/`saveConfig` 保留（暂不删，避免牵动其它引用）。

- [x] **Step 1: 加 `getRawConfig()`**（依赖：Task 3.Step 1）
  在 `api.js` 的 `api` 对象加 `getRawConfig: function () { return fetch(API_BASE + "/desktop/config/raw").then(r => { if(!r.ok) throw new Error("Raw config fetch failed: "+r.status); return r.json(); }); }`。返回 `{text, path, exists}`。`// SPEC-CFGUI-UI-002a`

- [x] **Step 2: 加 `saveRawConfig(text)`**（依赖：Task 3.Step 1）
  `saveRawConfig: function (text) { return fetch(API_BASE+"/desktop/config/raw", {method:"PUT", body:JSON.stringify({text:text}), headers:{"Content-Type":"application/json"}}).then(...) }`，沿用 `saveConfig` 的 `r.text()→try JSON.parse→!r.ok 抛 payload.error` 容错。返回 `{saved, restartRequired, unknownKeys}`。`// SPEC-CFGUI-UI-003d`

- [ ] **Step 3: 提交**（依赖：Step 1–2）— 未执行（用户未要求提交）
  `git commit -m "feat: 前端 api 增加 raw 配置读写\n\nSPEC-CFGUI-UI-002a, SPEC-CFGUI-UI-003d"`

**验证**：手动验收时由 Task 12 覆盖（配置模态能拉取/保存 raw 文本）。旧 `getConfig/saveConfig` 仍在。

---

### Task 5: 前端 state.js + ui.js — 载入与关闭

**Files:** Modify `state.js`, `ui.js`

state.js：
- 新增 `configRawText: ""`（当前编辑器内容来源）、`configRawBaseline: ""`（基准，用于脏态/放弃）。`configDirty`/`configSaving`/`configSaveResult` 复用；`configChangedSections` 不再使用（可保留以免牵动 `updateConfigActionBar`，但状态文案改为 SPEC-CFGUI-UI-003b）。

ui.js：
- `loadConfig()` 改为 `api.getRawConfig().then(resp => { state.configRawText = resp.text; state.configRawBaseline = resp.text; ... renderConfigTab(); })`；失败时 `renderConfigTab()` 进入只读+错误态（UI-002c）。
- `openConfigModal()`：打开时若已有 `configRawBaseline` 仍重新 `loadConfig()` 拉取最新文件（配置可能被 Agent/外部改动），保证基准与磁盘一致。
- `closeConfigModal()`：若 `state.configDirty` → `window.confirm("有未保存的更改，确定丢弃并关闭？")`，取消则 return 不关闭（UI-004a）。

- [x] **Step 1: state.js 加字段**
  在 `state.js` 加 `configRawText: ""`、`configRawBaseline: ""`（紧邻既有 `config`/`configDirty` 等字段）。`configChangedSections` 保留不用。`// SPEC-CFGUI-UI-002`

- [x] **Step 2: ui.js `loadConfig()` 改用 raw**（依赖：Step 1 + Task 4.Step 1）
  改写为 `api.getRawConfig().then(resp => { state.configRawText = resp.text; state.configRawBaseline = resp.text; state.configDirty=false; state.configSaveResult=null; state.configSaving=false; renderConfigTab(); })`；`.catch` → 置错误态（`state.configLoadError=true`，`renderConfigTab()` 渲染只读+错误提示）。`// SPEC-CFGUI-UI-002a, SPEC-CFGUI-UI-002c`

- [x] **Step 3: ui.js `openConfigModal()` 总是重载**（依赖：Step 2）
  去掉「已有 config 则跳过加载」分支，打开时始终 `loadConfig()`，保证基准文本与磁盘最新一致（配置可能被 Agent/外部改动）。`// SPEC-CFGUI-UI-002a`

- [x] **Step 4: ui.js `closeConfigModal()` 脏态确认**（依赖：Step 1）
  入口加 `if (state.configDirty && !window.confirm("有未保存的更改，确定丢弃并关闭？")) return;`，确认后再隐藏模态并清脏态。（与 Task 8 二选一落点，避免重复；本步为首选落点。）`// SPEC-CFGUI-UI-004a`

- [ ] **Step 5: 提交**（依赖：Step 1–4）— 未执行（用户未要求提交）
  `git commit -m "feat: 配置模态改用 raw 文本载入 + 关闭脏态确认\n\nSPEC-CFGUI-UI-002, SPEC-CFGUI-UI-004a"`

**验证**：Task 12 手动验收 SPEC-CFGUI-TST-007（打开载入文本）、TST-009（脏态关闭确认）。

---

### Task 6: 前端 config.js — 纯文本编辑器渲染与逻辑

**Files:** Modify `config.js`

重写 `renderConfigTab()`，删除 `sections` 数组、`renderCollectorFields`、`renderConfigField`、`readSectionConfig`、`countChangedConfigSections`、`getOriginalConfigValue`、`configValuesEqual` 等结构化逻辑（UI-001b）。新结构渲染进 `#config-grid`：
```js
function renderConfigTab() {
  var grid = state.dom.configGrid;
  var html = ""
    + '<div class="config-editor-toolbar">'
    +   '<button id="test-llm-btn" class="btn btn-sm btn-outline">测试 LLM 连接</button>'
    +   '<button id="test-embedding-btn" class="btn btn-sm btn-outline">测试 Embedding 连接</button>'
    + '</div>'
    + '<textarea id="config-raw-editor" class="config-raw-editor" spellcheck="false" wrap="off">'
    +   escHtml(state.configRawText || "")
    + '</textarea>'
    + renderConfigActionBar();
  grid.innerHTML = html;
  updateConfigActionBar();
}
```
- `currentEditorText()`：`document.getElementById("config-raw-editor").value`。
- 脏态：`updateConfigDirtyState()` 改为 `state.configDirty = currentEditorText() !== state.configRawBaseline`（UI-003b）。`handleConfigFieldChange`（input 事件）触发它。
- `updateConfigActionBar()`：状态文案改为 `configSaving?"正在保存...":configDirty?"有未保存更改":"没有未保存更改"`；按钮禁用条件不变。
- `discardConfigChanges()`：`state.configRawText = state.configRawBaseline; state.configDirty=false; renderConfigTab();`（UI-003c）。
- `saveAllConfig()`：读 `currentEditorText()` → `api.saveRawConfig(text)`：成功后 `state.configRawText = state.configRawBaseline = text; configDirty=false; configSaveResult={type:"success",msg:"保存成功"}`，若 `resp.restartRequired.length` 追加「（需重启后端: …）」，若 `resp.unknownKeys.length` 追加「未知键: …」（UI-003e）；失败 `configSaveResult={type:"error",msg:错误文本}`，不更新基准（UI-003f）。保存中按钮禁用并显示「保存中...」。
- 测试取值助手（供 events.js 用）：`readLlmConfigFromEditor()` / `readEmbeddingConfigFromEditor()`——用轻量行解析编辑器文本中相关 dotted key（`llm.base-url`/`llm.model`/`llm.api-key`；`embedding.*`），映射成测试端点期望的驼峰字段（`{baseUrl, model, apiKey}` / `{embeddingBaseUrl, embeddingModel, embeddingApiKey}`），缺失则不传（端点回退既有逻辑，UI-005c）。

- [x] **Step 1: 重写 `renderConfigTab()` 渲染编辑器**（依赖：Task 5.Step 1）
  按上文模板渲染到 `#config-grid`：工具条（`test-llm-btn`/`test-embedding-btn`）+ `<textarea id="config-raw-editor" class="config-raw-editor" spellcheck="false" wrap="off">` 填 `escHtml(state.configRawText)` + `renderConfigActionBar()`，末尾 `updateConfigActionBar()`。若 `state.configLoadError` 则 textarea 只读并显示错误提示。`// SPEC-CFGUI-UI-001a, SPEC-CFGUI-UI-002b, SPEC-CFGUI-UI-002c`

- [x] **Step 2: 删除结构化渲染函数**（依赖：Step 1）
  移除 `sections` 数组、`renderCollectorFields`、`renderConfigField`、`readSectionConfig`、`readChangedConfig`、`countChangedConfigSections`、`getOriginalConfigValue`、`configValuesEqual`。`// SPEC-CFGUI-UI-001b`

- [x] **Step 3: 脏态改为文本比较**（依赖：Step 1）
  加 `function currentEditorText(){ var el=document.getElementById("config-raw-editor"); return el?el.value:""; }`。`updateConfigDirtyState()` 改为 `state.configDirty = currentEditorText() !== state.configRawBaseline; if(state.configDirty) state.configSaveResult=null; updateConfigActionBar();`。`// SPEC-CFGUI-UI-003b`

- [x] **Step 4: `updateConfigActionBar()` 文案**（依赖：Step 3）
  状态文案改为 `configSaving?"正在保存...":configDirty?"有未保存更改":"没有未保存更改"`；放弃/保存按钮禁用条件 `configSaving || !configDirty` 不变。`// SPEC-CFGUI-UI-003a, SPEC-CFGUI-UI-003b`

- [x] **Step 5: `discardConfigChanges()` 恢复基准**（依赖：Step 1）
  `state.configRawText = state.configRawBaseline; state.configDirty=false; state.configSaveResult=null; renderConfigTab();`。`// SPEC-CFGUI-UI-003c`

- [x] **Step 6: `saveAllConfig()` 走 raw 保存**（依赖：Step 1 + Task 4.Step 2）
  读 `var text=currentEditorText()`；`state.configSaving=true; updateConfigActionBar();` → `api.saveRawConfig(text).then(resp=>{ state.configRawText=state.configRawBaseline=text; configDirty=false; configSaving=false; configSaveResult={type:"success",msg:"保存成功"}; if(resp.restartRequired&&resp.restartRequired.length) msg+="（需重启后端: "+join+"）"; if(resp.unknownKeys&&resp.unknownKeys.length) msg+="（未知键: "+join+"）"; renderConfigTab(); }).catch(err=>{ configSaving=false; configSaveResult={type:"error",msg:"保存失败: "+err.message}; updateConfigActionBar(); })`。`// SPEC-CFGUI-UI-003d, SPEC-CFGUI-UI-003e, SPEC-CFGUI-UI-003f, SPEC-CFGUI-GOAL-004`

- [x] **Step 7: 编辑器取值助手**（依赖：Step 1）
  `function parseEditorProps()`：按行解析 `currentEditorText()`，跳过空行/`#` 注释，按首个 `=`/`:` 拆 key/value，返回 map。`readLlmConfigFromEditor()` → `{baseUrl:m["llm.base-url"], model:m["llm.model"], apiKey:m["llm.api-key"]}`（缺失字段不带）；`readEmbeddingConfigFromEditor()` → `{embeddingBaseUrl, embeddingModel, embeddingApiKey}`。`// SPEC-CFGUI-UI-005b, SPEC-CFGUI-UI-005c`

- [ ] **Step 8: 提交**（依赖：Step 1–7）— 未执行（用户未要求提交）
  `git commit -m "feat: 配置模态改为纯文本编辑器（渲染/脏态/保存/放弃/测试取值）\n\nSPEC-CFGUI-UI-001, SPEC-CFGUI-UI-003, SPEC-CFGUI-UI-005, SPEC-CFGUI-GOAL-001"`

**验证**：Task 11 静态脚本（textarea/renderConfigTab/取值助手存在、无结构化残留）+ Task 12 手动 TST-007/008/010。

---

### Task 7: 前端 index.html — 模态主体容器

**Files:** Modify `index.html`

- `#config-grid` 内的占位文案保留为初始 loading（`renderConfigTab` 会覆盖）。无需改 `#config-grid` id（保持 init.js / events.js 既有 dom 引用与事件委托不变）。实际编辑器 DOM 由 `renderConfigTab()` 注入，故 index.html 改动最小：仅确认 `config-modal-body > #config-grid` 结构存在即可（必要时去掉分区相关的占位文字）。

- [x] **Step 1: 校准模态主体占位**（依赖：无）
  在 `index.html` 确认 `#config-modal > .config-modal-body > #config-grid` 结构存在；把 `#config-grid` 内占位文案从「加载配置中...」保留即可（`renderConfigTab()` 注入编辑器时覆盖）。无需新增静态编辑器 DOM（由 JS 注入），保持 init.js/events.js 既有 dom 引用与事件委托不变。`// SPEC-CFGUI-UI-001a`

- [ ] **Step 2: 提交（如有改动）**（依赖：Step 1）— N/A：index.html 无需改动，且用户未要求提交
  仅在确有文案/结构微调时 `git commit -m "chore: 配置模态主体占位对齐文本编辑器\n\nSPEC-CFGUI-UI-001a"`。

**验证**：Task 12 打开模态确认主体为编辑器而非分区表单（SPEC-CFGUI-TST-007）。

---

### Task 8: 前端 events.js — 测试按钮取值 + 关闭确认

**Files:** Modify `events.js`

- 配置区委托里 `test-llm-btn` 改为 `var llmConfig = readLlmConfigFromEditor();`（替换 `readSectionConfig("llm")`）；`test-embedding-btn` 同理用 `readEmbeddingConfigFromEditor()`。按钮文案改「测试 LLM 连接」/「测试 Embedding 连接」，loading 文案「测试中...」。结果写入 `configSaveResult` 并 `updateConfigActionBar()`（UI-005a/b）。
- 关闭按钮与遮罩点击：包一层确认——`function tryCloseConfig() { if (state.configDirty && !window.confirm("有未保存的更改，确定丢弃并关闭？")) return; closeConfigModal(); }`，把 `configCloseBtn` 与 `.config-modal-overlay` 的监听换成 `tryCloseConfig`（UI-004a）。（实现可放在 ui.js 的 `closeConfigModal` 内做确认，二选一，避免重复。）
- `input`/`change` 监听仍挂在 `#config-grid`，textarea 的 input 会冒泡触发脏态。

- [x] **Step 1: 测试按钮取值改造**（依赖：Task 6.Step 7）
  在 `events.js` 配置委托里，`test-llm-btn` 分支把 `readSectionConfig("llm")` 换成 `readLlmConfigFromEditor()`；`test-embedding-btn` 换成 `readEmbeddingConfigFromEditor()`。按钮初始/loading 文案改「测试 LLM 连接」/「测试 Embedding 连接」/「测试中...」，结果写 `state.configSaveResult` 并 `updateConfigActionBar()`。`// SPEC-CFGUI-UI-005a, SPEC-CFGUI-UI-005b, SPEC-CFGUI-GOAL-005`

- [x] **Step 2: 关闭监听核对**（依赖：Task 5.Step 4）
  确认 `configCloseBtn` 与 `.config-modal-overlay` 仍绑定 `closeConfigModal`（脏态确认已在 Task 5.Step 4 的 `closeConfigModal` 内实现），无需重复加 confirm。若改为在 events.js 包 `tryCloseConfig`，则 Task 5.Step 4 不再加 confirm（二选一，避免双弹窗）。`// SPEC-CFGUI-UI-004a`

- [ ] **Step 3: 提交**（依赖：Step 1–2）— 未执行（用户未要求提交）
  `git commit -m "feat: 配置测试按钮从编辑器文本取值\n\nSPEC-CFGUI-UI-005, SPEC-CFGUI-GOAL-005"`

**验证**：Task 12 手动 SPEC-CFGUI-TST-010（测试按钮基于编辑文本）+ TST-009（关闭确认不双弹）。

---

### Task 9: 前端 styles.css — 编辑器样式

**Files:** Modify `styles.css`

- `.config-editor-toolbar`：flex、右对齐、间距、底边距。
- `.config-raw-editor`：占满模态主体宽度、`min-height` 充足（如 `420px`，随 `.config-modal-body` 滚动）、等宽字体（`font-family: var(--mono, ui-monospace, Consolas, monospace)`）、`font-size:13px`、`line-height:1.5`、`white-space:pre`、`tab-size:4`、`resize:vertical`、配色沿用 `var(--bg-input)/--text-primary/--border-color`、`:focus` 边框高亮。
- 复用既有 `.config-action-bar` 样式（仍由 `renderConfigActionBar` 输出）。

- [x] **Step 1: 加 `.config-editor-toolbar`**（依赖：无）
  在 `styles.css` 加：flex、`justify-content:flex-end`、`gap`、`margin-bottom`。`// SPEC-CFGUI-UI-005a`

- [x] **Step 2: 加 `.config-raw-editor`**（依赖：无）
  占满 `.config-modal-body` 宽度、`min-height:420px`、等宽字体（`ui-monospace, Consolas, monospace`）、`font-size:13px`、`line-height:1.5`、`white-space:pre`、`tab-size:4`、`resize:vertical`、配色用 `var(--bg-input)/--text-primary/--border-color`、`:focus` 边框高亮。`// SPEC-CFGUI-UI-001a`

- [ ] **Step 3: 提交**（依赖：Step 1–2）— 未执行（用户未要求提交）
  `git commit -m "feat: 配置纯文本编辑器样式\n\nSPEC-CFGUI-UI-001a"`

**验证**：Task 11 静态脚本校验 `.config-raw-editor` 存在 + Task 12 目视等宽编辑器。

---

### Task 10: 后端单测

**Files:** Create `DesktopConfigControllerTest.java`, `UserConfigStoreRawTest.java`

`UserConfigStoreRawTest`（`@TempDir Path dir`）：
- `readRaw` 文件不存在 → `""`。
- `saveRaw(text)` 后 `readRaw()` 逐字符等于 `text`（含注释、空行、中文值）→ round-trip 保真（TST-003）。

`DesktopConfigControllerTest`（构造 `new DesktopConfigController(Config.testDefaults(tempDir), new UserConfigStore(tempDir))`，调端点级/静态纯方法，不碰 Javalin `Context`）：
- `parseProperties` 合法文本通过；非法 `\u` 转义文本抛异常（TST-004 基础）。
- `buildRawResponse()`：空目录 → `exists=false` 且 `text` 含受支持键（模板，TST-002）；先 `saveRaw` 再调 → `exists=true` 且 `text` 等于写入内容（TST-001）。
- `applyRawSave("llm.model=gpt-4o-mini\n")` → `restartRequired` 含 `llm.model`；仅改非重启键（如 `llm.api-key`）→ 不含（TST-005）。
- `applyRawSave` 含未知键（如 `foo.bar=1`）→ `unknownKeys` 含 `foo.bar` 且文件已写入（saved，不阻断）（TST-006）。
- `applyRawSave` 非法 `\u` 文本 → 抛校验异常且 `userStore.readRaw()` 保持原值（不落盘）（TST-004）。

> 用既有 `Config.testDefaults(Path baseDir)` 工厂构造默认 Config（见近期提交 0579f4f），传入 `@TempDir`。

- [x] **Step 1: `UserConfigStoreRawTest`**（依赖：Task 1）
  `@TempDir Path dir`，`new UserConfigStore(dir)`：① `readRaw()` 文件不存在 → `""`；② `saveRaw(s)` 后 `readRaw()` 逐字符 `assertEquals(s, ...)`，`s` 含注释行、空行、中文值（round-trip 保真）。`// SPEC-CFGUI-TST-003`

- [x] **Step 2: `DesktopConfigControllerTest`**（依赖：Task 2）
  `new DesktopConfigController(Config.testDefaults(tempDir), new UserConfigStore(tempDir))`，直测端点级/静态纯方法：
  - `buildRawResponse()` 空目录 → `exists==false` 且 `text` 含 `llm.base-url` 等受支持键（模板）。`// SPEC-CFGUI-TST-002`
  - 先 `userStore.saveRaw("llm.model=x\n")` 再 `buildRawResponse()` → `exists==true` 且 `text` 等于写入内容。`// SPEC-CFGUI-TST-001`
  - `parseProperties("a=\\uZZZZ")` 抛异常；`applyRawSave` 同样非法文本抛异常且 `userStore.readRaw()` 保持原值（不落盘）。`// SPEC-CFGUI-TST-004`
  - `applyRawSave("llm.model=gpt-4o-mini\n")` → `restartRequired` 含 `llm.model`；仅 `llm.api-key=sk-x` → 不含。`// SPEC-CFGUI-TST-005`
  - `applyRawSave("foo.bar=1\n")` → `unknownKeys` 含 `foo.bar` 且 `readRaw()` 已写入（不阻断）。`// SPEC-CFGUI-TST-006`

- [x] **Step 3: 跑测试**（依赖：Step 1–2）
  `mvn test -pl self-analyst-app -Dtest=UserConfigStoreRawTest,DesktopConfigControllerTest` → 全绿。

- [ ] **Step 4: 提交**（依赖：Step 3）— 未执行（用户未要求提交）
  `git commit -m "test: 配置 raw 读写与控制器助手单测\n\nSPEC-CFGUI-TST-001..006"`

**验证**：本 Task 即 SPEC-CFGUI-TST-001..006 的承载。

---

### Task 11: 前端静态校验脚本

**Files:** Create `scripts/check-desktop-config-editor.ps1`

仿 `scripts/check-desktop-behavior-advice.ps1`，`Assert-Contains` 校验：
- `index.html` / 运行期 DOM 由 JS 注入，故主要校验 `config.js`：含 `id="config-raw-editor"`、`function renderConfigTab`、`escHtml`、`readLlmConfigFromEditor`、`readEmbeddingConfigFromEditor`；**不**再含结构化标志（如 `renderCollectorFields`）。
- `api.js`：含 `getRawConfig`、`saveRawConfig`、`/desktop/config/raw`。
- `styles.css`：含 `.config-raw-editor`。
- 末尾 `Write-Output "desktop config editor static checks passed"`。

- [x] **Step 1: 写脚本**（依赖：Task 4, 6, 9）
  `scripts/check-desktop-config-editor.ps1` 仿 `check-desktop-behavior-advice.ps1` 的 `Assert-Contains`：
  - `config.js`：`id="config-raw-editor"`、`function\s+renderConfigTab`、`escHtml`、`readLlmConfigFromEditor`、`readEmbeddingConfigFromEditor`；且 `-notmatch 'renderCollectorFields'`（确认结构化残留已删）。
  - `api.js`：`getRawConfig`、`saveRawConfig`、`/desktop/config/raw`。
  - `styles.css`：`\.config-raw-editor`。
  - 末尾 `Write-Output "desktop config editor static checks passed"`。`// SPEC-CFGUI-TST-007`

- [x] **Step 2: 运行脚本**（依赖：Step 1）
  `powershell -ExecutionPolicy Bypass -File scripts/check-desktop-config-editor.ps1` → 输出 passed。

- [ ] **Step 3: 提交**（依赖：Step 2）— 未执行（用户未要求提交）
  `git commit -m "feat: 配置文本编辑器前端静态校验脚本\n\nSPEC-CFGUI-TST-007"`

**验证**：脚本输出 "desktop config editor static checks passed"（SPEC-CFGUI-TST-007）。

---

### Task 12: 全量构建与验收

- [x] **Step 1: 全量 `mvn test`**（依赖：Task 1–11）
  BUILD SUCCESS；旧 `configRoundTrip` 集成项不回归——验证 `GET/PUT /desktop/config` 兼容。`// SPEC-CFGUI-API-003a`

- [x] **Step 2: 跑静态脚本**（依赖：Task 11）
  `check-desktop-config-editor.ps1`、`check-desktop-chat-tab.ps1` 均输出 passed。`// SPEC-CFGUI-TST-007`

- [ ] **Step 3: 手动验收**（依赖：Step 1–2；启动桌面端，对照 spec §8）— 待用户手动执行（需启动桌面端 GUI，无法在此环境自动完成）：
  - 打开「配置」→ 纯文本编辑器（非分区表单），载入当前文件文本。`// SPEC-CFGUI-TST-007`
  - 编辑 → 操作栏「有未保存更改」、按钮启用 → 保存 → 「保存成功」，改 `llm.model` 时追加重启提示。`// SPEC-CFGUI-TST-008`
  - 有未保存更改时点 X/遮罩 → 二次确认；取消保持打开，确认才关闭（不双弹）。`// SPEC-CFGUI-TST-009`
  - 点「测试 LLM 连接」基于编辑器文本发起测试并展示结果。`// SPEC-CFGUI-TST-010`

- [ ] **Step 4: 维护追溯矩阵 + 收尾提交**（依赖：Step 1–3）— 追溯矩阵已核对一致；提交待用户要求
  确认 spec 末尾追溯矩阵的目标文件与实际改动一致（已核对：matrix 列出的文件均已按实现改动）；如手动验收暴露问题则修复并 `git commit`。`// SPEC-CFGUI-GOAL-001`

**验证**：spec §8 测试规格表 SPEC-CFGUI-TST-007..010 手动通过 + 全量构建绿。

---

### Task 13: 版本历史（保留最近 10 个版本）

**Files:** Create `ConfigHistoryStore.java`、`ConfigHistoryStoreTest.java`；Modify `DesktopConfigController.java`、`DesktopServer.java`、`api.js`、`state.js`、`ui.js`、`config.js`、`events.js`、`styles.css`、`DesktopConfigControllerTest.java`、`check-desktop-config-editor.ps1`

- [x] **Step 1: `ConfigHistoryStore`**（`{memoryDir}/config-history.json`，原子写）
  `add(name,summary,text)` newest-first 入列并裁剪到 `MAX_VERSIONS=10`；`list()`/`get(id)`/`updateSummary(id,summary)`。`// SPEC-CFGUI-VER-DEC-001, SPEC-CFGUI-VER-DEC-003`
- [x] **Step 2: 控制器接入版本记录**
  `applyRawSave` 保存后 `recordVersion(oldP,newP,text)`：确定性键名级 `computeDiffSummary` 即时摘要 + `formatVersionName` 时间戳命名；端点 `getConfigHistory`/`getConfigVersion`（未知 id→404）。`// SPEC-CFGUI-VER-API-001, SPEC-CFGUI-VER-API-002, SPEC-CFGUI-VER-API-003, SPEC-CFGUI-VER-DEC-002`
- [x] **Step 3: 异步 LLM 摘要精炼（仅键名）**
  `refineSummaryAsync` 单 daemon 线程，**只发送变更键名**、绝不发送取值/原文；无 key 回退确定性摘要，不阻断保存。`// SPEC-CFGUI-VER-DEC-004`
- [x] **Step 4: 路由**：`GET /desktop/config/history`、`GET /desktop/config/history/{id}`。`// SPEC-CFGUI-VER-API-001/002`
- [x] **Step 5: 前端历史面板**
  `api.getConfigHistory/getConfigVersion`；state 三字段；`config.js` 工具区「历史版本 (n)」按钮 + 可展开列表（名称+摘要、查看内联预览、切换载入编辑器作为未保存更改）；保存成功刷新列表；`ui.js` 打开模态加载历史；`events.js` 委托三类点击；`styles.css` `.config-history-*`。`// SPEC-CFGUI-VER-UI-001, SPEC-CFGUI-VER-UI-002, SPEC-CFGUI-VER-UI-003, SPEC-CFGUI-VER-NON-001`
- [x] **Step 6: 测试 + 静态校验**
  `ConfigHistoryStoreTest`(3) + `DesktopConfigControllerTest` 增 5 项（命名/diff/快照/保留）；`check-desktop-config-editor.ps1` 增历史断言。`// SPEC-CFGUI-VER-TST-001..005`

**验证**：后端单测全绿（含 newest-first / 保留 10 / 404 / 命名格式 / diff）；静态脚本 passed。

---

### Task 14: 评审修复（P1 密钥外发 / P2 编码 / P2 端口）

**Files:** Modify `DesktopConfigController.java`、`UserConfigStore.java`、`Config.java`、`events.js`、`UserConfigStoreRawTest.java`

- [x] **Step 1: P1 密钥外发**
  `refineSummaryAsync` 改为只发送键名级 diff（见 Task 13.Step 3），消除 `api-key` 经原文外泄。`// SPEC-CFGUI-VER-DEC-004`
- [x] **Step 2: P2 UTF-8 编码一致**
  `UserConfigStore.loadUser()`/`loadClasspathDefaults()`、`Config.load()` 的用户/默认配置读取改用 `Reader`/UTF-8；`save()` 显式 UTF-8。补 `saveRaw("…中文…")→loadUser()` round-trip 测试。`// SPEC-CFGUI-DEC-005`
- [x] **Step 3: P2 Web 仪表盘端口**
  `events.js` Web Dashboard 按钮端口改取自 `/desktop/status` 的 `aw.webUrl/port`，不再依赖 raw 模式下已不填充的 `state.config`。`// SPEC-CFGUI-API-003`

**验证**：全量 `mvn test` BUILD SUCCESS；新增 UTF-8 round-trip 测试通过；静态脚本 passed。已合并 PR #6。

---

### Traceability Matrix

| Spec ID | Task(s) |
|---------|---------|
| SPEC-CFGUI-DEC-001..004 | Tasks 1, 2 |
| SPEC-CFGUI-DEC-005 | Task 14 |
| SPEC-CFGUI-VER-DEC-001..004 | Task 13, 14 |
| SPEC-CFGUI-VER-API-001..003 | Task 13 |
| SPEC-CFGUI-VER-UI-001..003 | Task 13 |
| SPEC-CFGUI-VER-NON-001..002 | Task 13 |
| SPEC-CFGUI-VER-TST-001..005 | Task 13 |
| SPEC-CFGUI-GOAL-001 | Tasks 6, 12 |
| SPEC-CFGUI-GOAL-002 | Tasks 2, 6 |
| SPEC-CFGUI-GOAL-003 | Task 1 |
| SPEC-CFGUI-GOAL-004 | Tasks 2, 6 |
| SPEC-CFGUI-GOAL-005 | Task 8 |
| SPEC-CFGUI-GOAL-006 | Task 2 |
| SPEC-CFGUI-UI-001 | Tasks 6, 7 |
| SPEC-CFGUI-UI-002 | Tasks 4, 5, 6 |
| SPEC-CFGUI-UI-003 | Task 6 |
| SPEC-CFGUI-UI-004 | Tasks 5, 8 |
| SPEC-CFGUI-UI-005 | Tasks 6, 8 |
| SPEC-CFGUI-API-001 | Tasks 2, 3 |
| SPEC-CFGUI-API-002 | Tasks 1, 2 |
| SPEC-CFGUI-API-003 | Tasks 3, 12 |
| SPEC-CFGUI-NON-001..004 | Tasks 1, 2 |
| SPEC-CFGUI-TST-001..006 | Task 10 |
| SPEC-CFGUI-TST-007 | Task 11 |
| SPEC-CFGUI-TST-008..010 | Task 12 |
