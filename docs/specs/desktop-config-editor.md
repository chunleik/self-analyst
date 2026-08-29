# SelfAnalyst 桌面端配置文本编辑器 SDD 规格说明书

> Specification-Driven Development spec. 本文档定义桌面端「配置」入口由结构化表单改为纯文本编辑器的行为契约。实现必须可追溯至本文档中的规格 ID。

> **当前契约说明**：本文第 2–8 节保留最初 `.properties` 方案的设计记录；当前实现已由
> [`config-toml.md`](config-toml.md) 修订为直接编辑 `config.toml`。发生冲突时以该 TOML 规格为准；
> 配置历史功能已移除，见本文第 9 节。

---

## 1. 文档元信息

| 属性 | 值 |
|------|-----|
| 功能名称 | 桌面端配置改为纯文本编辑（直接编辑 `config.properties`） |
| 文档状态 | Implemented（已合并 PR #6，2026-06-22） |
| 日期 | 2026-06-22 |
| 目标平台 | SelfAnalyst Tauri 桌面端内嵌 WebView，Windows 优先 |
| 规格前缀 | `SPEC-CFGUI-*` |
| 主要前端入口 | `self-analyst-app/src/main/resources/desktop-ui/index.html`（`#config-modal`） |
| 主要前端逻辑 | `self-analyst-app/src/main/resources/desktop-ui/`（config/ui/events/api/state.js） |
| 主要样式文件 | `self-analyst-app/src/main/resources/desktop-ui/styles.css` |
| 主要后端逻辑 | `self-analyst-app/src/main/java/com/selfanalyst/desktop/controller/DesktopConfigController.java` |
| 路由注册 | `self-analyst-app/src/main/java/com/selfanalyst/desktop/DesktopServer.java` |
| 配置存储 | `self-analyst-app/src/main/java/com/selfanalyst/desktop/store/UserConfigStore.java`（`{memoryDir}/config.properties`，UTF-8 读写） |

---

## 2. 背景与当前状态

桌面端「配置」是一个模态框（`#config-modal`，由 `configOpenBtn → openConfigModal()` 打开）。
其主体当前由 `renderConfigTab()` 渲染成多个结构化分区（LLM / ActivityWatch / 采集 / 音频 /
Agent / 桌面 / Embedding / 联网搜索），每个分区是若干 input、toggle、下拉框（见 `config.js`）。
保存时前端把变更的字段按分区拼成 JSON，调用 `PUT /desktop/config`，后端
`DesktopConfigController` 把驼峰键映射成点分键写入 `config.properties`。

用户诉求：**不要弹出结构化配置界面，改为弹出纯文本编辑框，让用户直接编辑配置文本。**

配置最终持久化为标准 Java `.properties` 文本（仅含用户覆盖项，带分区注释，见
`UserConfigStore.save()`）。因此「直接编辑文本」自然映射为：在编辑器里直接编辑该文件的原始文本。

---

## 3. 设计结论（决策与取舍）

- **SPEC-CFGUI-DEC-001**：编辑器编辑的是**用户级覆盖文件 `config.properties` 的原始文本**，
  不是「默认值 + 用户覆盖」的合并有效配置。
  - *取舍*：合并视图会混入大量默认值，模糊「哪一项是用户真正设置的覆盖」，且回写会把默认值固化成覆盖。直接编辑用户文件语义最清晰，与磁盘文件一一对应。
  - *可发现性补偿*：文件为空/不存在时，编辑器载入一段**带注释的模板文本**（见 SPEC-CFGUI-API-001b），注释列出受支持键及其默认值，供用户参考。

- **SPEC-CFGUI-DEC-002**：`GET .../raw` 返回的文本**不脱敏**（包括 `llm.api-key`、
  `embedding.api-key`、`websearch.api-key`）。
  - *取舍*：结构化接口对 api-key 脱敏（`****`）；但纯文本编辑下若脱敏，保存时会把脱敏串原样写回、破坏真实密钥。编辑器面对的是文件本身，必须见到真实文本。该文件本就是本地明文存储，且后端仅绑定 `localhost`，风险与直接打开文件等同。

- **SPEC-CFGUI-DEC-003**：保存采用**整文件覆盖**语义（前端提交完整文本，后端原样落盘），
  不做「逐键 diff 合并」。用户对文件文本拥有完全控制权（含注释、键顺序、删除某行即移除覆盖）。

- **SPEC-CFGUI-DEC-004**：受支持键白名单与「需重启键集合」**不在本特性内重新定义**，
  复用既有约定（`SPEC-CFG-TOOL-002`、`SPEC-CFG-TOOL-003`、
  `DesktopConfigController.RESTART_REQUIRED`），避免两处定义漂移。本特性只**消费**它们。

- **SPEC-CFGUI-DEC-005**：用户级 `config.properties` 统一以 **UTF-8** 读写。raw 编辑器与结构化
  `save()` 均按 UTF-8 写入；运行时读取（`UserConfigStore.loadUser()`、`Config.load()` 的用户覆盖、
  classpath 默认）一律改用 `Reader`/UTF-8，杜绝「编辑器按 UTF-8 显示、运行时按 ISO-8859-1 解码」
  导致的中文路径/模型名乱码。这是有意偏离 `.properties` 默认 Latin-1 语义、换取所见即所得。

---

## 4. 目标

- **SPEC-CFGUI-GOAL-001**：桌面端配置编辑从结构化表单改为纯文本编辑器，用户直接编辑配置文本。
- **SPEC-CFGUI-GOAL-002**：编辑器的内容来源与回写目标均为用户级 `config.properties` 原始文本。
- **SPEC-CFGUI-GOAL-003**：保存须原子写入，并尽量保真用户输入（注释、键顺序、空行）。
- **SPEC-CFGUI-GOAL-004**：保存后须告知用户哪些被改动的键需要重启后端才能生效。
- **SPEC-CFGUI-GOAL-005**：保留 LLM / Embedding 连接测试能力。
- **SPEC-CFGUI-GOAL-006**：非法配置文本不得落盘，须给出可读的错误反馈。

---

## 5. 交互与 UI 契约

### SPEC-CFGUI-UI-001：模态主体替换为文本编辑器

- **SPEC-CFGUI-UI-001a**：「配置」入口（`configOpenBtn → openConfigModal()`）打开的模态框，
  其主体（原 `#config-grid` 承载的结构化分区）替换为**单个纯文本编辑器**：多行文本域，
  等宽字体，允许换行；编辑区支持纵向滚动以容纳长配置。
- **SPEC-CFGUI-UI-001b**：不再渲染任何结构化分区、字段、开关或下拉框（原 `renderConfigTab()`
  的分区/字段渲染、`renderCollectorFields()`、`renderConfigField()` 不再用于该模态）。

### SPEC-CFGUI-UI-002：载入与初始内容

- **SPEC-CFGUI-UI-002a**：模态打开时通过 `GET /desktop/config/raw` 载入当前配置文本并填入编辑器。
- **SPEC-CFGUI-UI-002b**：当配置文件不存在或为空时，编辑器载入后端返回的**模板文本**
  （带注释的受支持键清单与默认值），而非空白。
- **SPEC-CFGUI-UI-002c**：载入失败时，编辑器置为只读并展示错误提示，不允许在未知初始状态上保存。

### SPEC-CFGUI-UI-003：脏状态、保存与放弃

- **SPEC-CFGUI-UI-003a**：在配置文本编辑器上方显示操作栏「配置更改 / 放弃更改 / 保存更改」
  （`config-action-bar`）。
- **SPEC-CFGUI-UI-003b**：脏状态由「编辑器当前文本」与「最近一次载入/保存后的基准文本」逐字符比较得出；
  无差异时「放弃更改」「保存更改」均禁用。
- **SPEC-CFGUI-UI-003c**：「放弃更改」把编辑器恢复为基准文本。
- **SPEC-CFGUI-UI-003d**：「保存更改」调用 `PUT /desktop/config/raw` 提交完整文本；保存进行中按钮显示「保存中...」并禁用。
- **SPEC-CFGUI-UI-003e**：保存成功后，把基准文本更新为已保存文本并清除脏状态，显示「保存成功」；
  若响应 `restartRequired` 非空，追加「（需重启后端: <键列表>）」；若 `unknownKeys` 非空，追加未知键提示。
- **SPEC-CFGUI-UI-003f**：保存失败显示错误信息（含后端返回的校验错误文本），不清除脏状态、不更新基准文本。

### SPEC-CFGUI-UI-004：关闭保护

- **SPEC-CFGUI-UI-004a**：通过 X 按钮或点击遮罩关闭模态时，若存在未保存更改，须先二次确认；
  用户确认丢弃后才关闭，取消则保持模态打开。无未保存更改时直接关闭。

### SPEC-CFGUI-UI-005：连接测试

- **SPEC-CFGUI-UI-005a**：编辑器工具区提供「测试 LLM 连接」「测试 Embedding 连接」两个按钮。
- **SPEC-CFGUI-UI-005b**：点击时解析编辑器**当前文本**中相关键（如 `llm.base-url`/`llm.model`/
  `llm.api-key`，及 `embedding.*` 对应键），调用既有 `POST /desktop/config/test-llm`、
  `POST /desktop/config/test-embedding` 并展示结果；结果文案须明确标注 `LLM` 或 `Embedding`，
  不能只显示无来源的「连接成功」。
- **SPEC-CFGUI-UI-005c**：当文本中相应 api-key 缺省或为占位符时，测试请求按既有端点语义回退到环境变量/已存文件值（不在本特性内改变测试端点的回退逻辑）。

---

## 6. 后端接口契约

### SPEC-CFGUI-API-001：`GET /desktop/config/raw`

- **SPEC-CFGUI-API-001a**：返回 JSON `{ "text": <string>, "path": <string>, "exists": <bool> }`。
  `text` 为用户配置文件 `config.properties` 的**原始文本**（不脱敏、不合并默认值，见
  `SPEC-CFGUI-DEC-001`/`-002`）；`path` 为该文件绝对路径；`exists` 表示文件是否已存在。
- **SPEC-CFGUI-API-001b**：当文件不存在或为空时，`exists=false`，`text` 为**模板文本**：
  一段以注释（`#`）形式列出受支持键及其默认值的 `.properties` 文本，所有键默认注释掉，供用户取消注释后填写。

### SPEC-CFGUI-API-002：`PUT /desktop/config/raw`

- 请求体：`{ "text": <string> }`。
- **SPEC-CFGUI-API-002a**：先按 Java `.properties` 语义解析校验提交文本；解析失败（如非法
  `\u` 转义）时返回 HTTP 400 与可读错误信息，**不修改**磁盘文件。
- **SPEC-CFGUI-API-002b**：校验通过后，将提交文本**原样**（verbatim，保留注释、键顺序、空行）
  原子写入 `config.properties`（temp 文件 + rename），不经「分区重排/重新格式化」。
- **SPEC-CFGUI-API-002c**：把解析后的新属性集与保存前的旧用户属性集比较，返回
  `{ "saved": true, "restartRequired": [<键>...], "unknownKeys": [<键>...] }`：
  - `restartRequired`：发生新增/变更/删除且属于重启键集合（`SPEC-CFG-TOOL-003` /
    `DesktopConfigController.RESTART_REQUIRED`）的点分键。
  - `unknownKeys`：不在受支持键白名单（`SPEC-CFG-TOOL-002`）内的键，仅作为**警告**返回，**不阻断**保存。
- **SPEC-CFGUI-API-002d**：写入失败返回 HTTP 500 与错误信息，磁盘文件保持原状（原子写入保证）。

### SPEC-CFGUI-API-003：兼容性

- **SPEC-CFGUI-API-003a**：既有 `GET /desktop/config`、`PUT /desktop/config`（结构化）端点**保留**
  不删除，以维持集成测试 `configRoundTrip` 与向后兼容；桌面配置模态不再调用它们。
- **SPEC-CFGUI-API-003b**：`POST /desktop/config/test-llm`、`POST /desktop/config/test-embedding`
  行为不变。
- **SPEC-CFGUI-API-003c**：Agent 的 `ConfigTools`（`getConfig`/`setConfigValue`，见
  `SPEC-CFG-TOOL-001`）不受本特性影响。

---

## 7. 非目标

- **SPEC-CFGUI-NON-001**：不新增任何配置键，不改变 `config.properties` 的路径与 `.properties` 格式。
  **（已废弃，2026-07-02）**「不改变 `.properties` 格式」部分被 [config-toml.md](config-toml.md)
  （`SPEC-TOML-*`）取代：用户级配置迁移为 `{memoryDir}/config.toml`；「不新增配置键」约束仍然有效
  （由 `SPEC-TOML-NON-002` 延续）。
- **SPEC-CFGUI-NON-002**：不提供语法高亮、键名自动补全、行内校验提示（留待后续迭代）。
- **SPEC-CFGUI-NON-003**：不改变 `config.properties` 的查找优先级（用户文件 > classpath 默认 > 硬编码默认）。
- **SPEC-CFGUI-NON-004**：不在 raw 文本中对敏感值做脱敏（见 `SPEC-CFGUI-DEC-002`）。

---

## 8. 测试规格

| 规格 ID | 测试 | 预期 |
|---------|------|------|
| SPEC-CFGUI-TST-001 | `GET /desktop/config/raw`，文件已存在 | 返回 `text` 等于文件原始内容，`exists=true`，`path` 指向 `config.properties` |
| SPEC-CFGUI-TST-002 | `GET /desktop/config/raw`，文件不存在 | `exists=false`，`text` 为带注释的模板文本 |
| SPEC-CFGUI-TST-003 | `PUT .../raw` 提交合法文本 → 再 `GET .../raw` | round-trip 文本逐字符一致（注释、顺序保真） |
| SPEC-CFGUI-TST-004 | `PUT .../raw` 提交非法 properties（非法 `\u` 转义） | 返回 400，磁盘文件未被修改 |
| SPEC-CFGUI-TST-005 | `PUT .../raw` 修改重启键（如 `llm.model`） | `restartRequired` 含该键；仅改非重启键时不含 |
| SPEC-CFGUI-TST-006 | `PUT .../raw` 含未知键 | `unknownKeys` 含该键，`saved=true`（不阻断） |
| SPEC-CFGUI-TST-007 | 打开配置模态（手动/UI） | 主体为纯文本编辑器，载入当前配置文本，无结构化分区表单 |
| SPEC-CFGUI-TST-008 | 编辑文本 → 保存（手动/UI） | 脏态联动按钮，保存成功提示；`restartRequired` 非空时追加重启提示 |
| SPEC-CFGUI-TST-009 | 存在未保存更改时关闭模态（手动/UI） | 触发二次确认；取消则保持打开，确认才丢弃关闭 |
| SPEC-CFGUI-TST-010 | 点击「测试 LLM 连接」（手动/UI） | 基于编辑器当前文本中的 LLM 键发起测试并展示结果 |

---

## 9. 配置历史（已移除）

配置编辑器不再创建、读取或展示历史版本；`/desktop/config/history` 接口和保存后的快照/摘要流程
均已移除。升级前可能存在的 `{memoryDir}/config-history.json` 保留在磁盘，不主动删除。

## 规格追溯矩阵

| 规格 ID | 对应文件/组件 | 验证方式 |
|---------|--------------|---------|
| SPEC-CFGUI-DEC-001..004 | 本 spec（设计决策） | 代码审查 |
| SPEC-CFGUI-DEC-005 | `UserConfigStore.java`、`Config.java` | 单元测试（UTF-8 round-trip）、代码审查 |
| SPEC-CFGUI-GOAL-001..006 | 全特性 | 验收测试 |
| SPEC-CFGUI-UI-001..005 | `desktop-ui/config.js`、`index.html`、`ui.js`、`events.js`、`api.js`、`state.js`、`styles.css` | 手动/验收测试、代码审查 |
| SPEC-CFGUI-API-001 | `DesktopConfigController.java`、`DesktopServer.java`、`UserConfigStore.java` | 单元测试 |
| SPEC-CFGUI-API-002 | `DesktopConfigController.java`、`UserConfigStore.java` | 单元测试 |
| SPEC-CFGUI-API-003 | `DesktopServer.java`、`DesktopConfigController.java` | 单元测试、代码审查 |
| SPEC-CFGUI-NON-001..004 | 全特性 | 代码审查 |
| SPEC-CFGUI-TST-001..010 | 测试用例 | 单元测试 + 手动/验收测试 |
