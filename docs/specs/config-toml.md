# SelfAnalyst 用户配置与桌面编辑器 SDD 规格说明书

> **迁移状态：** 现行行为契约已迁移至 [`user-configuration`](../../openspec/specs/user-configuration/spec.md)。本文档仅保留为旧 ID、历史背景和源码追溯，不再独立维护。

> 本文档定义用户级配置文件 **TOML v1.0**（`config.toml`）的当前行为契约：格式与键映射、
> 便携包配置路径、加载优先级，以及桌面端 raw 编辑器。本文同时接管旧版配置编辑器
> 规格中仍有效的 `SPEC-CFGUI-*` 契约。

---

## 1. 文档元信息

| 属性 | 值 |
|------|-----|
| 功能名称 | 便携包 TOML 用户配置（`./data/config/config.toml`） |
| 文档状态 | 已实现（当前契约） |
| 日期 | 2026-07-02 |
| 目标平台 | Windows 优先（中文路径、反斜杠路径为一等场景） |
| 规格前缀 | `SPEC-TOML-*`、`SPEC-CFGUI-*` |
| 取代关系 | 接管旧版配置编辑器规格的有效契约；`SPEC-CFGUI-NON-001` 已由 TOML 格式取代 |
| 主要后端逻辑 | `self-analyst-app`：`config/Config.java`、`desktop/store/UserConfigStore.java`、`desktop/controller/DesktopConfigController.java` |
| 主要前端逻辑 | `self-analyst-app/src/main/resources/desktop-ui/`（config/api/events/state.js） |
| 配置存储 | `./data/config/config.toml`（UTF-8，相对于便携包根目录） |

---

## 2. 背景与动机

早期版本使用 `.properties`，桌面端配置模态直接编辑该文件。当前便携包使用
`./data/config/config.toml`，并与 `memory.dir` 分离，使配置可以决定记忆和索引的存储位置：

1. **Windows 路径静默损坏**：`memory.dir=D:\docs` 中 `\d` 是未知转义，Java Properties 解析时
   丢弃反斜杠得到 `D:docs`；raw 保存校验只拦非法 `\u` 转义，这种损坏不报错、直接生效。
2. **编码语义补丁**：`.properties` 默认 Latin-1，项目已通过 `SPEC-CFGUI-DEC-005` 全链路
   有意偏离为 UTF-8。TOML 规范强制 UTF-8，该偏离不再需要。
3. **无类型**：布尔/整数/浮点全是字符串，非法值只能在运行时静默回退；列表靠逗号拼接字符串。

TOML 的字面量字符串（`'D:\docs'`）、原生类型、数组、带行列号的解析错误，直接消除以上三点。

---

## 3. 设计结论（决策与取舍）

- **SPEC-TOML-DEC-001**：便携包的用户级覆盖文件固定为
  `./data/config/config.toml`。classpath 默认值仍使用 `application.properties`。
  - *取舍*：配置目录不依赖 `memory.dir`，因此 TOML 中的 `memory.dir` 能在启动时生效。
- **SPEC-TOML-DEC-002**：**内部键命名空间不变**。运行时、白名单（`SPEC-CFG-TOOL-002`）、
  重启键集合（`SPEC-CFG-TOOL-003`）继续使用扁平点分键
  （如 `llm.api-key`）。TOML 表在解析后**拍平**为点分键（见 SPEC-TOML-FMT-002），
  格式迁移对这些消费方透明。
  - *取舍*：若同步改造内部键体系，白名单/重启集合/ConfigTools/结构化端点全部联动，风险不成比例。
- **SPEC-TOML-DEC-003**：解析后的 TOML 值**归一化为字符串属性集**再进入既有配置管道
  （`Properties` 等价物），运行时 `Config.load()` 的字符串解析逻辑不变。类型校验发生在
  raw 保存入口（见 SPEC-TOML-API-001c），而非运行时。
- **SPEC-TOML-DEC-004**：raw 编辑路径继续**逐字保真**（verbatim 读写，复用
  `SPEC-CFGUI-API-002b` 语义）；结构化写入（`PUT /desktop/config`、`ConfigTools.setConfigValue`）
  **整文件重新生成** TOML（分区表、不保留用户注释）——与既有 `.properties` 结构化 `save()`
  丢注释的行为持平，不新增保真承诺。
- **SPEC-TOML-DEC-005（已废止）**：不再执行 `.properties` 自动迁移或读取旧路径。
- **SPEC-TOML-DEC-006（已废止）**：配置历史功能已移除；旧 `config-history.json` 不主动删除，
  但应用不再读取或写入。
- **SPEC-TOML-DEC-007**：TOML 解析器作为第三方依赖引入（Java 无标准库实现）。选型、版本号
  属构建配置与实现细节；spec 层只约束能力：TOML v1.0 兼容、解析错误含行/列位置、
  无需注释保真写回（raw 逐字写、结构化重新生成，均不经库序列化注释）。

---

## 4. 目标

- **SPEC-TOML-GOAL-001**：用户级配置持久化为 `./data/config/config.toml`（TOML v1.0，UTF-8）。
- **SPEC-TOML-GOAL-002**：Windows 反斜杠路径在字面量字符串中所见即所得，不再被转义规则吞噬。
- **SPEC-TOML-GOAL-003**：配置路径与 `memory.dir` 解耦，`memory.dir` 可由 TOML 覆盖。
- **SPEC-TOML-GOAL-004**：桌面端 raw 编辑器与连接测试在 TOML 下行为等价可用。
- **SPEC-TOML-GOAL-005**：非法 TOML 文本与已知键的类型错误不得落盘，错误反馈含行/列与可读原因。
- **SPEC-TOML-GOAL-006**：白名单、重启键等点分键消费方无感知（拍平后契约不变）。

---

## 5. 配置编辑器基础契约（`SPEC-CFGUI-*`）

本节把原独立配置编辑器规格中仍有效的契约收敛到当前文档。凡涉及文件格式、路径和校验器的
细节，以后续 `SPEC-TOML-*` 条款为准。

### 5.1 设计与目标

- **SPEC-CFGUI-DEC-001**：编辑器展示和保存的是用户级覆盖文件原文，不把默认值合并成已启用配置。
- **SPEC-CFGUI-DEC-002**：raw 文本不脱敏。编辑器必须显示真实 API key，否则保存脱敏占位符会破坏密钥。接口始终受回环 Host/Origin 边界保护；只有 Tauri 管理启动时额外要求 desktop token。standalone 模式的同机进程边界见 [`../../SECURITY.md`](../../SECURITY.md)。
- **SPEC-CFGUI-DEC-003**：raw 保存提交完整文本并逐字覆盖，不做逐键 diff；成功写入使用同目录临时文件后 `REPLACE_EXISTING` 替换目标。当前实现未请求 Java `ATOMIC_MOVE`，因此不承诺崩溃级原子 rename。
- **SPEC-CFGUI-DEC-004**：受支持键和需重启键由后端单一声明维护，UI 与 Agent 工具不得复制另一套列表。
- **SPEC-CFGUI-DEC-005**：用户配置统一使用 UTF-8。
- **SPEC-CFGUI-GOAL-001**：桌面配置入口提供纯文本编辑器，不展示结构化配置表单。
- **SPEC-CFGUI-GOAL-002**：编辑器读取和保存用户级 `config.toml` 原文，不把默认值固化为覆盖。
- **SPEC-CFGUI-GOAL-003**：合法文本经同目录临时文件替换写入，并尽量保留注释、顺序和空行。
- **SPEC-CFGUI-GOAL-004**：保存后明确列出需要重启后端才能生效的键。
- **SPEC-CFGUI-GOAL-005**：保留 LLM 与 Embedding 连接测试能力。
- **SPEC-CFGUI-GOAL-006**：非法配置不得进入目标文件，错误必须可读。

### 5.2 UI 契约

- **SPEC-CFGUI-UI-001a**：配置入口打开单个等宽、多行、可滚动的纯文本编辑器。
- **SPEC-CFGUI-UI-001b**：配置模态不再展示结构化分区、字段、开关或下拉框。
- **SPEC-CFGUI-UI-002a**：打开时调用 `GET /desktop/config/raw` 并填充编辑器。
- **SPEC-CFGUI-UI-002b**：文件不存在或为空时展示完整注释模板，不自动写盘。
- **SPEC-CFGUI-UI-002c**：载入失败时编辑器只读并禁止保存。
- **SPEC-CFGUI-UI-003a**：界面提供配置状态、放弃更改和保存更改操作。
- **SPEC-CFGUI-UI-003b**：脏状态按当前文本与最近一次成功载入/保存的基准文本逐字符比较。
- **SPEC-CFGUI-UI-003c**：放弃更改恢复基准文本。
- **SPEC-CFGUI-UI-003d**：保存提交完整文本；保存期间按钮显示进行中并禁用重复提交。
- **SPEC-CFGUI-UI-003e**：保存成功后更新基准、清除脏状态，并显示 `restartRequired` 与 `unknownKeys`。
- **SPEC-CFGUI-UI-003f**：保存失败显示完整校验错误，不清除脏状态、不改变基准。
- **SPEC-CFGUI-UI-004a**：存在未保存修改时，关闭按钮和遮罩关闭都必须先确认。
- **SPEC-CFGUI-UI-005a**：编辑器保留“测试 LLM 连接”和“测试 Embedding 连接”入口。
- **SPEC-CFGUI-UI-005b**：连接测试使用编辑器当前文本中的 URL、模型和密钥。
- **SPEC-CFGUI-UI-005c**：密钥缺省或仍为占位符时，按后端既有语义回退到有效配置。

### 5.3 API 与非目标

- **SPEC-CFGUI-API-001a**：`GET /desktop/config/raw` 至少返回 `{ text, path, exists }`。
- **SPEC-CFGUI-API-001b**：文本不脱敏；文件不存在或为空时返回完整模板。
- **SPEC-CFGUI-API-002a**：`PUT /desktop/config/raw` 在写盘前完成语法、结构和已知键类型校验。
- **SPEC-CFGUI-API-002b**：校验通过后先写同目录临时文件，再替换目标，保留注释、顺序和空行；不承诺 `ATOMIC_MOVE`。
- **SPEC-CFGUI-API-002c**：响应包含 `saved`、`restartRequired` 和 `unknownKeys`。
- **SPEC-CFGUI-API-002d**：校验失败或临时文件写入失败不得替换目标；替换失败返回错误且不得报告保存成功。当前实现不承诺自动清理替换失败后残留的 `.tmp`。
- **SPEC-CFGUI-API-003a**：`GET/PUT /desktop/config` 结构化端点保持兼容。
- **SPEC-CFGUI-API-003b**：LLM 与 Embedding 连接测试端点保持兼容。
- **SPEC-CFGUI-API-003c**：Agent `ConfigTools` 继续通过同一用户配置存储读写。
- **SPEC-CFGUI-NON-001（已取代）**：旧“不改变 `.properties` 格式”约束已由 `SPEC-TOML-*` 取代。
- **SPEC-CFGUI-NON-002**：不承诺语法高亮、自动补全或行内诊断。
- **SPEC-CFGUI-NON-003**：不改变本文件定义的配置加载优先级。
- **SPEC-CFGUI-NON-004**：raw 文本不对敏感值做脱敏。

---

## 6. 格式与键映射契约

### SPEC-TOML-FMT-001：文件与编码

- **SPEC-TOML-FMT-001a**：用户级配置文件为 `./data/config/config.toml`，UTF-8 读写，
  语法遵循 TOML v1.0。
- **SPEC-TOML-FMT-001b**：文件仅含用户覆盖项（不合并默认值），与 `SPEC-CFGUI-DEC-001` 语义一致。

### SPEC-TOML-FMT-002：表 → 点分键拍平

- **SPEC-TOML-FMT-002a**：解析后的 TOML 文档按「表名与键名以 `.` 连接」拍平为点分键：
  `[llm]` 下的 `api-key` ≡ `llm.api-key`；`[aw.collection]` 下的 `window` ≡
  `aw.collection.window`；顶层点分键（`llm.api-key = "..."`）与表内键等价。
- **SPEC-TOML-FMT-002b**：同一点分键经不同写法重复定义时遵循 TOML 语义（重复定义即解析错误），
  不做「后者覆盖」的宽容处理。

### SPEC-TOML-FMT-003：值类型与归一化

- **SPEC-TOML-FMT-003a**：标量值归一化规则：字符串原样；布尔 → `"true"`/`"false"`；
  整数/浮点 → 十进制字符串表示。归一化结果进入既有字符串属性管道（SPEC-TOML-DEC-003）。
- **SPEC-TOML-FMT-003b**：**基本类型数组**（元素全为字符串/整数/浮点/布尔）归一化为
  逗号拼接字符串（如 `extensions = ["md", "txt"]` → `"md,txt"`），与既有逗号列表键
  （`file.watch.extensions`、`file.watch.excludeGlobs` 等）语义对接。
- **SPEC-TOML-FMT-003c**：以下 TOML 结构不受支持，出现即校验失败（400，不落盘）：
  内联表/子表作为值嵌入数组、日期时间类型、混合类型数组。
- **SPEC-TOML-FMT-003d**：已知键（受支持键白名单 `SPEC-CFG-TOOL-002` 及模板列出的键）带有
  声明类型（string/boolean/integer/float/list）。校验采取**宽容匹配**：值为声明类型本身，
  或为可无损解析到声明类型的字符串（如 `port = "5600"`）均通过；不可解析（如
  `aw.port = "abc"`、`llm.temperature = true`）则整体校验失败。未知键不做类型校验，
  仅按既有 `unknownKeys` 警告语义返回。
- **SPEC-TOML-FMT-003e**：文件过滤配置还必须执行语义校验：扩展名只允许规范 token 或单独 `*`，
  空列表表示不采集；目录名不得含路径分隔符；排除 glob 必须是非否定的 root-relative 有效模式；
  `maxFileSizeKb` 不得为负。任一失败均整体拒绝保存，不得静默忽略后放宽采集范围。

### SPEC-TOML-FMT-004：模板文本

- **SPEC-TOML-FMT-004a**：文件不存在/为空时返回的模板（原 `SPEC-CFGUI-API-001b`）改为 TOML 形式：
  按分区组织 `[llm]`、`[aw]`、`[wiki]`、`[embedding]`、`[agent]`、`[desktop]` 等表头，
  **全部**受支持键以 `#` 注释形式列出并标注默认值与类型；每个键前须同时提供中文说明与
  English 说明。模板本身必须仍可作为合法 TOML 解析，且不产生任何用户覆盖项。
- **SPEC-TOML-FMT-004b**：模板注释须包含 Windows 路径写法指引：路径值使用单引号字面量字符串
  （`'D:\docs'`）或正斜杠。

---

## 7. 配置路径与加载契约

### SPEC-TOML-LOAD-001：便携包路径

- **SPEC-TOML-LOAD-001a**：后端以进程工作目录为便携包根目录，只读写
  `./data/config/config.toml`。Tauri 启动 Java 时必须将工作目录设为 EXE 所在目录。
- **SPEC-TOML-LOAD-001b**：配置目录不依赖 `memory.dir`；后端先读取 TOML，再解析有效
  `memory.dir`。
- **SPEC-TOML-LOAD-001c**：不自动迁移或读取 `config.properties`、`{memoryDir}/config.toml`
  及 `~/.self-analyst/config.properties`。

### SPEC-TOML-LOAD-002：加载优先级

- **SPEC-TOML-LOAD-002a**：配置解析优先级（高到低）为：
  1. 支持该入口的环境变量或 JVM system property
  2. `./data/config/config.toml`（TOML 解析 + 拍平归一化）
  3. classpath `application.properties` 默认值
  4. 硬编码默认值
- **SPEC-TOML-LOAD-002b**：`Config.load()` 与 `UserConfigStore` 对用户级 TOML 的读取行为
  一致，不得出现两处解析结果不一致。
- **SPEC-TOML-LOAD-002c**：`aw.port` 不接受环境变量覆盖，只来自用户 TOML 或 classpath
  默认值。Tauri 不得自行解析另一份端口配置，也不得向 Java 注入端口覆盖值。

### SPEC-TOML-PORT-001：桌面启动端口握手

- **SPEC-TOML-PORT-001a**：Java 后端是 `aw.port` 的唯一解析者。完成 Javalin 启动并注册带认证的
  desktop lifecycle 路由后，将有效端口原子写入 Tauri 为本次启动提供的临时握手文件。
- **SPEC-TOML-PORT-001b**：Tauri 必须等待握手文件，使用其中端口执行健康检查、创建 WebView、生成
  Web 版桌面链接及发送退出请求；不得回退到硬编码端口继续运行。
- **SPEC-TOML-PORT-001c**：握手文件名每次启动唯一，内容只能是 `1..65535` 的十进制端口；消费后和
  退出时均应清理。
- **SPEC-TOML-PORT-001d**：内嵌 AW 模式下，Java 内部客户端使用的 `aw.base-url` 必须由有效
  `aw.port` 派生；仅外部 AW 模式保留独立配置 `aw.base-url` 的语义。
- **SPEC-TOML-PORT-001e**：握手文件发布与后端健康检查分别最多等待 30 秒；任一阶段失败时 Tauri
  必须退出并依靠受管 Job Object 回收 Java 子进程，不得长期占用单实例锁。

---

## 8. 后端接口契约

### SPEC-TOML-API-001：raw 端点改为 TOML

- **SPEC-TOML-API-001a**：`GET /desktop/config/raw` 返回结构不变
  （`{ text, path, exists }`），`text` 为 `config.toml` 原始文本（不脱敏，沿用
  `SPEC-CFGUI-DEC-002` 取舍），`path` 指向 `config.toml`；文件不存在/为空时 `text` 为
  TOML 模板（SPEC-TOML-FMT-004）。
- **SPEC-TOML-API-001b**：`PUT /desktop/config/raw` 先按 TOML v1.0 解析校验提交文本；
  解析失败返回 HTTP 400，错误信息含**行/列位置**与原因，不修改磁盘文件。
- **SPEC-TOML-API-001c**：解析通过后执行已知键类型校验（SPEC-TOML-FMT-003d）与结构校验
  （SPEC-TOML-FMT-003c）；任一失败返回 400 并列出违规键与期望类型，不落盘。
- **SPEC-TOML-API-001d**：全部校验通过后逐字写入同目录 temp，再以 `REPLACE_EXISTING` 替换 `config.toml`；
  成功后的 round-trip 逐字符一致，响应结构与 `restartRequired`/`unknownKeys` 语义不变
  （`SPEC-CFGUI-API-002c`，比较对象为拍平后的点分键集）。
- **SPEC-TOML-API-001e**：`GET /desktop/config/raw` 响应在既有 `{ text, path, exists }`
  基础上新增只读字段 `supportedKeys`：按 `SupportedKeys` 声明顺序的
  `[{ key, type, assignment }]` 列表，`key` 为点分键、`type` 为声明类型小写、`assignment`
  为该键默认值的顶层点分 TOML 赋值文本（引号规则与 `TomlSupport` 生成一致）。该字段为
  兼容既有客户端保留；当前桌面 UI 不依赖它，不改变 `text`/`exists` 既有语义。

### SPEC-TOML-API-002：结构化端点与 ConfigTools

- **SPEC-TOML-API-002a**：`GET /desktop/config`、`PUT /desktop/config`（结构化）保留，
  行为契约不变；其底层读写目标改为 `config.toml`（写入走整文件重新生成，SPEC-TOML-DEC-004）。
- **SPEC-TOML-API-002b**：`ConfigTools.getConfig`/`setConfigValue`（`SPEC-CFG-TOOL-001..004`）
  对外契约不变（点分键、白名单、重启提示、临时文件替换），底层持久化目标改为 `config.toml`。
- **SPEC-TOML-API-002c**：`POST /desktop/config/test-llm`、`POST /desktop/config/test-embedding`
  行为不变。

---

## 9. 桌面端 UI 契约

- **SPEC-TOML-UI-001**：配置模态的纯文本编辑器交互契约（载入、脏态、保存、放弃、关闭保护）
  全部沿用 `SPEC-CFGUI-UI-001..004`，仅编辑对象变为 `config.toml` 文本；界面上展示的文件名/
  路径提示同步为 `config.toml`。
- **SPEC-TOML-UI-002**：保存失败（400）时，须把后端返回的行/列与违规键信息完整展示给用户
  （沿用 `SPEC-CFGUI-UI-003f` 的失败处理，不清脏态）。
- **SPEC-TOML-UI-003**：「测试 LLM 连接」「测试 Embedding 连接」按钮改为从编辑器当前
  **TOML 文本**中解析相关键（`llm.base-url`/`llm.model`/`llm.api-key` 及 `embedding.*`，
  含表内写法与顶层点分写法两种形态）；解析不出时按既有回退语义（`SPEC-CFGUI-UI-005c`）。

### SPEC-TOML-UI-004：直接编辑配置文件

- **SPEC-TOML-UI-004a**：点击配置入口后直接弹出 `config.toml` 纯文本编辑器，不再显示
  「全部可配置项」折叠面板或逐项插入操作。
- **SPEC-TOML-UI-004b**：编辑器上方显示文件名与后端返回的实际文件路径；连接测试入口保留。
- **SPEC-TOML-UI-004c**：文件不存在/为空时，编辑器直接载入 `SPEC-TOML-FMT-004` 的完整模板，
  用户通过取消目标配置行的注释进行覆盖；仅取消注释或修改文本会进入脏态，打开模板本身不写盘。

---

## 10. 非目标

- **SPEC-TOML-NON-001**：classpath `application.properties` 仍作为内置默认值，不改为 TOML。
- **SPEC-TOML-NON-002**：不新增/删除/重命名任何配置键；点分键命名空间、白名单、
  重启键集合原样复用。
- **SPEC-TOML-NON-003**：不提供 TOML 语法高亮、自动补全、行内校验（沿 `SPEC-CFGUI-NON-002`）。
- **SPEC-TOML-NON-004**：结构化写入不保留用户注释（与既有行为持平，见 SPEC-TOML-DEC-004）。
- **SPEC-TOML-NON-005**：不提供旧配置路径的自动迁移或回滚工具。

---

## 11. 测试规格

| 规格 ID | 测试 | 预期 |
|---------|------|------|
| SPEC-CFGUI-TST-001..003 | raw GET/PUT round-trip；文件存在、缺失和合法保存 | `text/path/exists` 正确；合法文本逐字符往返一致 |
| SPEC-CFGUI-TST-004 | raw PUT 提交非法 TOML | 400，磁盘文件不变 |
| SPEC-CFGUI-TST-005..006 | 修改重启键、提交未知键 | `restartRequired`、`unknownKeys` 正确 |
| SPEC-CFGUI-TST-007..009 | 打开、编辑、保存、关闭配置模态 | 纯文本编辑、脏态、保存反馈和关闭确认符合 UI 契约 |
| SPEC-CFGUI-TST-010 | 使用编辑器当前文本测试连接 | 使用当前 LLM/Embedding 配置并标明测试目标 |
| SPEC-TOML-TST-001 | 解析含 `[llm]` 表、`[aw.collection]` 子表、顶层点分键的 TOML | 拍平结果与等价点分键集一致 |
| SPEC-TOML-TST-002 | 值为 `'D:\docs'` 字面量字符串 | 归一化后反斜杠原样保留 |
| SPEC-TOML-TST-003 | 布尔/整数/浮点/字符串数组值 | 按 SPEC-TOML-FMT-003a/b 归一化（数组逗号拼接） |
| SPEC-TOML-TST-004 | `PUT .../raw` 提交非法 TOML 语法 | 400，错误含行/列，磁盘未变 |
| SPEC-TOML-TST-005 | `PUT .../raw` 已知键类型不可解析（`aw.port = "abc"`） | 400，列出违规键与期望类型，磁盘未变 |
| SPEC-TOML-TST-006 | `PUT .../raw` 合法文本 → `GET .../raw` | round-trip 逐字符一致（注释、顺序保真） |
| SPEC-TOML-TST-007 | `PUT .../raw` 修改重启键 / 含未知键 | `restartRequired`/`unknownKeys` 语义与 `SPEC-CFGUI-API-002c` 一致 |
| SPEC-TOML-TST-008 | 便携包根目录下存在 `data/config/config.toml` | `Config.load()` 与桌面配置 API 读取同一文件 |
| SPEC-TOML-TST-009 | TOML 中设置 `memory.dir` | 配置位置不变，记忆目录按配置生效 |
| SPEC-TOML-TST-010 | `config.toml` 损坏不可解析 | 记录日志并按无用户覆盖继续启动 |
| SPEC-TOML-TST-011 | 中文值（路径/模型名）TOML round-trip | UTF-8 无乱码 |
| SPEC-TOML-TST-012 | `GET .../raw`，文件不存在 | `text` 为合法 TOML 模板，含全部支持键、默认值、类型、中英文说明与路径写法指引 |
| SPEC-TOML-TST-014 | `ConfigTools.setConfigValue` 写入后读回 | 值落入 `config.toml`，白名单/重启提示行为不变 |
| SPEC-TOML-TST-015 | 结构化 `PUT /desktop/config` 保存 | 重新生成的 `config.toml` 可被 raw 端点与 `Config.load()` 一致解析 |
| SPEC-TOML-TST-016 | 编辑器内测试 LLM 连接，键写在 `[llm]` 表内（手动/UI） | 正确解析出 base-url/model/api-key 并发起测试 |
| SPEC-TOML-TST-017 | `GET .../raw` 响应含 `supportedKeys`，逐项 `assignment` 可被 `parseAndFlatten` 还原为对应默认值 | `supportedKeys` 覆盖全部受支持键，顺序与 `SupportedKeys` 一致 |
| SPEC-TOML-TST-018 | 点击配置入口（手动/UI） | 直接显示文件名、实际路径与纯文本编辑器，不出现旧参考/插入面板 |
| SPEC-TOML-TST-019 | `config.toml` 设置 `aw.port` 后由桌面壳启动 | Java 监听该端口，Tauri 使用握手返回的同一端口完成健康检查和页面加载 |
| SPEC-TOML-TST-020 | 桌面启动握手端口为 `0`、越界值或非数字 | Tauri 拒绝该握手并退出，不使用硬编码端口兜底 |

---

## 规格追溯矩阵

| 规格 ID | 目标文件/组件 | 验证方式 |
|---------|--------------|---------|
| SPEC-CFGUI-DEC-*、SPEC-CFGUI-GOAL-*、SPEC-CFGUI-NON-* | 本文第 5 节；`UserConfigStore.java`、`DesktopConfigController.java` | 代码审查、单元测试 |
| SPEC-CFGUI-UI-* | `desktop-ui/config.js`、`index.html`、`ui.js`、`events.js`、`api.js`、`state.js`、`styles.css` | `check-desktop-config-editor.ps1`、UI 验收 |
| SPEC-CFGUI-API-* | `DesktopConfigController.java`、`DesktopServer.java`、`UserConfigStore.java` | `DesktopConfigControllerTest`、集成测试 |
| SPEC-TOML-DEC-001..007 | 本 spec（设计决策）；`config/TomlSupport.java`（DEC-003/004/007）、`config/Config.java`（DEC-001/005） | 代码审查 |
| SPEC-TOML-FMT-001..003 | `config/TomlSupport.java`（解析/拍平/归一化/类型校验）、`config/SupportedKeys.java`、`config/Config.java` | 单元测试 `TomlSupportTest`、`ConfigTest` |
| SPEC-TOML-FMT-004 | `config/TomlSupport.java#buildTemplate`、`desktop/controller/DesktopConfigController.java#buildTemplate` | 单元测试 `TomlSupportTest`、`DesktopConfigControllerTest` + 代码审查 |
| SPEC-TOML-LOAD-001..002 | `config/Config.java#resolveConfigDir/overlayUserConfig`、`desktop/store/UserConfigStore.java#loadUser` | 单元测试 `ConfigTest`、`UserConfigStoreRawTest` |
| SPEC-TOML-API-001 | `desktop/controller/DesktopConfigController.java`、`config/TomlSupport.java`、`desktop/store/UserConfigStore.java` | 单元测试 `DesktopConfigControllerTest` |
| SPEC-TOML-API-001e | `desktop/controller/DesktopConfigController.java#supportedKeyInfos`、`config/TomlSupport.java#emitAssignment` | 单元测试 `DesktopConfigControllerTest` |
| SPEC-TOML-API-002 | `desktop/controller/DesktopConfigController.java`、`desktop/store/UserConfigStore.java`、`ConfigTools`（`agent/tools`） | 单元测试 `DesktopConfigControllerTest` + `DesktopServerIntegrationTest.configRoundTripPersistsModel` |
| SPEC-TOML-UI-001..003 | `desktop-ui/config.js`（`parseEditorToml`、标签）、`api.js`、`styles.css` | 静态检查 `check-desktop-config-editor.ps1` + 手动/验收测试 |
| SPEC-TOML-UI-004 | `desktop-ui/config.js`、`events.js`、`state.js`、`ui.js`、`styles.css` | 静态检查 `check-desktop-config-editor.ps1` + 手动/验收测试 |
| SPEC-TOML-NON-001..005 | 全特性（`SupportedKeys` 键集不变、classpath 默认保留、结构化写入丢注释、无迁移工具） | 代码审查 |
| SPEC-TOML-TST-001..020（013 已废止） | 测试用例 | 单元测试 `TomlSupportTest`/`ConfigTest`/`UserConfigStoreRawTest`/`DesktopConfigControllerTest` + 手动/验收测试 |
