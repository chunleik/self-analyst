# SelfAnalyst 配置格式迁移 TOML SDD 规格说明书

> Specification-Driven Development spec. 本文档定义用户级配置文件由 Java `.properties`
> 迁移到 **TOML v1.0**（`config.toml`）的行为契约：格式与键映射、加载优先级、存量自动迁移、
> 桌面端 raw 编辑器与版本历史的适配。实现必须可追溯至本文档中的规格 ID。

---

## 1. 文档元信息

| 属性 | 值 |
|------|-----|
| 功能名称 | 用户级配置文件从 `.properties` 迁移到 TOML（`{memoryDir}/config.toml`） |
| 文档状态 | 设计中（Draft） |
| 日期 | 2026-07-02 |
| 目标平台 | Windows 优先（中文路径、反斜杠路径为一等场景） |
| 规格前缀 | `SPEC-TOML-*` |
| 取代关系 | 取代 `SPEC-CFGUI-NON-001`（原「不改变 `.properties` 格式」非目标，见 desktop-config-editor.md） |
| 主要后端逻辑 | `self-analyst-app`：`config/Config.java`、`desktop/store/UserConfigStore.java`、`desktop/store/ConfigHistoryStore.java`、`desktop/controller/DesktopConfigController.java` |
| 主要前端逻辑 | `self-analyst-app/src/main/resources/desktop-ui/`（config/api/events/state.js） |
| 配置存储 | `{memoryDir}/config.toml`（UTF-8） |

---

## 2. 背景与动机

用户级配置目前是 `{memoryDir}/config.properties`，桌面端配置模态是对该文件的**纯文本直接编辑**
（见 `docs/specs/desktop-config-editor.md`）。`.properties` 语义对手编用户有实际伤害：

1. **Windows 路径静默损坏**：`memory.dir=D:\docs` 中 `\d` 是未知转义，Java Properties 解析时
   丢弃反斜杠得到 `D:docs`；raw 保存校验只拦非法 `\u` 转义，这种损坏不报错、直接生效。
2. **编码语义补丁**：`.properties` 默认 Latin-1，项目已通过 `SPEC-CFGUI-DEC-005` 全链路
   有意偏离为 UTF-8。TOML 规范强制 UTF-8，该偏离不再需要。
3. **无类型**：布尔/整数/浮点全是字符串，非法值只能在运行时静默回退；列表靠逗号拼接字符串。

TOML 的字面量字符串（`'D:\docs'`）、原生类型、数组、带行列号的解析错误，直接消除以上三点。

---

## 3. 设计结论（决策与取舍）

- **SPEC-TOML-DEC-001**：迁移范围仅限**用户级覆盖文件**：`{memoryDir}/config.properties` →
  `{memoryDir}/config.toml`。classpath 默认值 `application.properties` 与 legacy
  `~/.self-analyst/config.properties` 覆盖层**保持 properties 格式与既有读取方式不变**。
  - *取舍*：classpath 默认是开发者维护、用户不可见；legacy 文件是只读兼容层。手编痛点只存在于
    用户级文件，收窄范围可显著降低迁移面。
- **SPEC-TOML-DEC-002**：**内部键命名空间不变**。运行时、白名单（`SPEC-CFG-TOOL-002`）、
  重启键集合（`SPEC-CFG-TOOL-003`）、版本历史键级 diff 继续使用扁平点分键
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
- **SPEC-TOML-DEC-005**：存量迁移为**后端启动时一次性自动转换**（见 §6），迁移后
  `config.properties` 重命名保留备份，避免"两份文件谁生效"的歧义。
- **SPEC-TOML-DEC-006**：迁移前的历史版本快照（properties 文本）**保留可查看、禁止切换**
  （见 SPEC-TOML-VER-002）。
  - *取舍*：允许切换会把 properties 文本载入编辑器、保存时必然 400，体验更差；直接清空历史
    则丢失用户追溯。查看保留 + 切换禁用是信息最全且不制造死路的方案。
- **SPEC-TOML-DEC-007**：TOML 解析器作为第三方依赖引入（Java 无标准库实现）。选型、版本号
  属实现细节，留给 plan；spec 层只约束能力：TOML v1.0 兼容、解析错误含行/列位置、
  无需注释保真写回（raw 逐字写、结构化重新生成，均不经库序列化注释）。

---

## 4. 目标

- **SPEC-TOML-GOAL-001**：用户级配置持久化为 `{memoryDir}/config.toml`（TOML v1.0，UTF-8）。
- **SPEC-TOML-GOAL-002**：Windows 反斜杠路径在字面量字符串中所见即所得，不再被转义规则吞噬。
- **SPEC-TOML-GOAL-003**：既有用户的 `config.properties` 在升级后自动、无损、一次性迁移。
- **SPEC-TOML-GOAL-004**：桌面端 raw 编辑器、版本历史、连接测试在 TOML 下行为等价可用。
- **SPEC-TOML-GOAL-005**：非法 TOML 文本与已知键的类型错误不得落盘，错误反馈含行/列与可读原因。
- **SPEC-TOML-GOAL-006**：白名单、重启键、键级 diff 等点分键消费方无感知（拍平后契约不变）。

---

## 5. 格式与键映射契约

### SPEC-TOML-FMT-001：文件与编码

- **SPEC-TOML-FMT-001a**：用户级配置文件为 `{memoryDir}/config.toml`，UTF-8 读写，
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
  （`file.watch.extensions`、`file.watch.exclude-globs` 等）语义对接。
- **SPEC-TOML-FMT-003c**：以下 TOML 结构不受支持，出现即校验失败（400，不落盘）：
  内联表/子表作为值嵌入数组、日期时间类型、混合类型数组。
- **SPEC-TOML-FMT-003d**：已知键（受支持键白名单 `SPEC-CFG-TOOL-002` 及模板列出的键）带有
  声明类型（string/boolean/integer/float/list）。校验采取**宽容匹配**：值为声明类型本身，
  或为可无损解析到声明类型的字符串（如 `port = "5600"`）均通过；不可解析（如
  `aw.port = "abc"`、`llm.temperature = true`）则整体校验失败。未知键不做类型校验，
  仅按既有 `unknownKeys` 警告语义返回。

### SPEC-TOML-FMT-004：模板文本

- **SPEC-TOML-FMT-004a**：文件不存在/为空时返回的模板（原 `SPEC-CFGUI-API-001b`）改为 TOML 形式：
  按分区组织 `[llm]`、`[aw]`、`[wiki]`、`[embedding]`、`[agent]`、`[desktop]` 等表头，
  受支持键以 `#` 注释形式列出并标注默认值与类型。
- **SPEC-TOML-FMT-004b**：模板注释须包含 Windows 路径写法指引：路径值使用单引号字面量字符串
  （`'D:\docs'`）或正斜杠。

---

## 6. 存量迁移契约

### SPEC-TOML-MIG-001：迁移时机与条件

- **SPEC-TOML-MIG-001a**：后端启动、用户配置首次被读取之前执行一次迁移检查：
  当 `{memoryDir}/config.toml` **不存在**且 `{memoryDir}/config.properties` **存在**时触发转换；
  `config.toml` 已存在时永不触发（幂等，properties 文件即使残留也被忽略）。
- **SPEC-TOML-MIG-001b**：转换内容：按 `.properties` 语义（UTF-8，与 `SPEC-CFGUI-DEC-005` 一致）
  解析旧文件为键值集，重新生成为分区表组织的 TOML 写入 `config.toml`。旧文件的注释不迁移
  （properties 注释与生成的 TOML 分区注释无对应关系）。
- **SPEC-TOML-MIG-001c**：转换成功后，将 `config.properties` 重命名为 `config.properties.bak`
  （已存在同名备份则覆盖）。转换失败（旧文件不可解析/写入失败）时**不重命名、不写入**
  `config.toml`，记录错误日志并按「无用户覆盖」继续启动，不得阻断后端。
- **SPEC-TOML-MIG-001d**：转换成功后创建一条版本历史快照（内容为迁移生成的 TOML 全文），
  摘要为确定性文案「从 config.properties 自动迁移」，复用 `SPEC-CFGUI-VER-*` 快照管道。

### SPEC-TOML-MIG-002：加载优先级

- **SPEC-TOML-MIG-002a**：配置解析优先级（高到低）更新为：
  1. 环境变量
  2. `{memoryDir}/config.toml`（TOML 解析 + 拍平归一化）
  3. `{memoryDir}/config.properties`（仅当 `config.toml` 不存在时读取；兼容迁移未运行的旧路径，如 CLI 单独启动）
  4. legacy `~/.self-analyst/config.properties`（properties 语义，保持不变）
  5. classpath `application.properties` 默认值
  6. 硬编码默认值
- **SPEC-TOML-MIG-002b**：`Config.load()` 与 `UserConfigStore` 对用户级文件的读取行为
  一致（同一优先级、同一拍平归一化规则），不得出现两处解析结果不一致。

---

## 7. 后端接口契约（对 `SPEC-CFGUI-API-*` 的修订）

### SPEC-TOML-API-001：raw 端点改为 TOML

- **SPEC-TOML-API-001a**：`GET /desktop/config/raw` 返回结构不变
  （`{ text, path, exists }`），`text` 为 `config.toml` 原始文本（不脱敏，沿用
  `SPEC-CFGUI-DEC-002` 取舍），`path` 指向 `config.toml`；文件不存在/为空时 `text` 为
  TOML 模板（SPEC-TOML-FMT-004）。
- **SPEC-TOML-API-001b**：`PUT /desktop/config/raw` 先按 TOML v1.0 解析校验提交文本；
  解析失败返回 HTTP 400，错误信息含**行/列位置**与原因，不修改磁盘文件。
- **SPEC-TOML-API-001c**：解析通过后执行已知键类型校验（SPEC-TOML-FMT-003d）与结构校验
  （SPEC-TOML-FMT-003c）；任一失败返回 400 并列出违规键与期望类型，不落盘。
- **SPEC-TOML-API-001d**：全部校验通过后逐字原子写入 `config.toml`（temp + rename，
  round-trip 逐字符一致），响应结构与 `restartRequired`/`unknownKeys` 语义不变
  （`SPEC-CFGUI-API-002c`，比较对象为拍平后的点分键集）。
- **SPEC-TOML-API-001e**：`GET /desktop/config/raw` 响应在既有 `{ text, path, exists }`
  基础上新增只读字段 `supportedKeys`：按 `SupportedKeys` 声明顺序的
  `[{ key, type, assignment }]` 列表，`key` 为点分键、`type` 为声明类型小写、`assignment`
  为该键默认值的顶层点分 TOML 赋值文本（引号规则与 `TomlSupport` 生成一致）。供
  `SPEC-TOML-UI-004` 参考面板消费；不改变 `text`/`exists` 既有语义。

### SPEC-TOML-API-002：结构化端点与 ConfigTools

- **SPEC-TOML-API-002a**：`GET /desktop/config`、`PUT /desktop/config`（结构化）保留，
  行为契约不变；其底层读写目标改为 `config.toml`（写入走整文件重新生成，SPEC-TOML-DEC-004）。
- **SPEC-TOML-API-002b**：`ConfigTools.getConfig`/`setConfigValue`（`SPEC-CFG-TOOL-001..004`）
  对外契约不变（点分键、白名单、重启提示、原子写），底层持久化目标改为 `config.toml`。
- **SPEC-TOML-API-002c**：`POST /desktop/config/test-llm`、`POST /desktop/config/test-embedding`
  行为不变。

---

## 8. 桌面端 UI 契约（对 `SPEC-CFGUI-UI-*` 的修订）

- **SPEC-TOML-UI-001**：配置模态的纯文本编辑器交互契约（载入、脏态、保存、放弃、关闭保护）
  全部沿用 `SPEC-CFGUI-UI-001..004`，仅编辑对象变为 `config.toml` 文本；界面上展示的文件名/
  路径提示同步为 `config.toml`。
- **SPEC-TOML-UI-002**：保存失败（400）时，须把后端返回的行/列与违规键信息完整展示给用户
  （沿用 `SPEC-CFGUI-UI-003f` 的失败处理，不清脏态）。
- **SPEC-TOML-UI-003**：「测试 LLM 连接」「测试 Embedding 连接」按钮改为从编辑器当前
  **TOML 文本**中解析相关键（`llm.base-url`/`llm.model`/`llm.api-key` 及 `embedding.*`，
  含表内写法与顶层点分写法两种形态）；解析不出时按既有回退语义（`SPEC-CFGUI-UI-005c`）。

### SPEC-TOML-UI-004：全部可配置项参考面板（补偿 `SPEC-CFGUI-DEC-001` 可发现性）

> 动机：编辑器只显示用户覆盖项（`SPEC-TOML-FMT-001b`/`SPEC-CFGUI-DEC-001`），文件非空时
> 用户看不到"还有哪些键可配、默认值是多少"。本面板为**只读参考**，与会落盘的编辑文本物理隔离，
> 不改变整文件覆盖保存语义（`SPEC-CFGUI-DEC-003`），因此默认值不会被固化成覆盖。

- **SPEC-TOML-UI-004a**：配置模态工具区提供「全部可配置项」入口（可折叠面板，与
  `SPEC-CFGUI-VER-UI-001` 历史面板同构）；无论文件是否为空、是否已有覆盖项均可展开。
- **SPEC-TOML-UI-004b**：面板按 `SupportedKeys` 声明顺序列出**全部受支持键**，每项显示
  点分键名、声明类型与默认值（以 `key = default` 的 TOML 赋值文本呈现，引号/字面量规则
  与 `TomlSupport` 生成一致）。数据源为 `GET /desktop/config/raw` 响应新增的
  `supportedKeys` 字段（`SPEC-TOML-API-001e`），不额外发请求。
- **SPEC-TOML-UI-004c**：每项提供「插入」动作，把该键以**顶层点分赋值**形式
  （`llm.model = "gpt-4o"`）插入编辑器**文本开头**（首个 `[table]` 之前，保证 TOML 归属
  无歧义），并进入脏态，由用户改值后走既有保存流程。插入采用默认值而非注释形式——用户显式
  点击即表示要覆盖该项，得到的是可编辑的真实覆盖项。
- **SPEC-TOML-UI-004d**：当某键已存在于编辑器当前文本（按 `SPEC-TOML-UI-003` 的轻量解析
  判定，含表内与顶层两种写法）时，其「插入」动作禁用并标注「已配置」，避免产生重复键导致保存
  400（TOML 重复键即解析错误，`SPEC-TOML-FMT-002b`）。
- **SPEC-TOML-UI-004e**：面板为纯参考，**不**触发任何写盘、不影响历史版本、不改变
  `SupportedKeys` 键集（`SPEC-TOML-NON-002`）。

---

## 9. 版本历史契约（对 `SPEC-CFGUI-VER-*` 的修订）

- **SPEC-TOML-VER-001**：版本快照结构新增 `format` 字段，取值 `"properties"` 或 `"toml"`；
  存量快照（无该字段）视为 `"properties"`，新快照一律写 `"toml"`。
  `GET /desktop/config/history`、`GET /desktop/config/history/{id}` 响应中携带该字段。
- **SPEC-TOML-VER-002**：`format` 与当前格式（`toml`）不一致的版本：**「查看」保留、
  「切换」禁用**；UI 上此类条目标注「旧格式（properties），仅可查看」。
- **SPEC-TOML-VER-003**：其余版本历史契约（保存即快照、保留最近 10、摘要仅发送键名的
  隐私约束 `SPEC-CFGUI-VER-DEC-004`）不变；键级 diff 基于拍平后的点分键计算，
  跨格式（properties 旧版 → toml 新版）比较时同样先各自拍平再 diff。

---

## 10. 非目标

- **SPEC-TOML-NON-001**：不迁移 classpath `application.properties` 与 legacy
  `~/.self-analyst/config.properties`（见 SPEC-TOML-DEC-001）。
- **SPEC-TOML-NON-002**：不新增/删除/重命名任何配置键；点分键命名空间、白名单、
  重启键集合原样复用。
- **SPEC-TOML-NON-003**：不提供 TOML 语法高亮、自动补全、行内校验（沿 `SPEC-CFGUI-NON-002`）。
- **SPEC-TOML-NON-004**：结构化写入不保留用户注释（与既有行为持平，见 SPEC-TOML-DEC-004）。
- **SPEC-TOML-NON-005**：不提供 TOML → properties 的反向回滚工具；回滚依赖迁移备份
  `config.properties.bak` 与旧版本二进制。

---

## 11. 测试规格

| 规格 ID | 测试 | 预期 |
|---------|------|------|
| SPEC-TOML-TST-001 | 解析含 `[llm]` 表、`[aw.collection]` 子表、顶层点分键的 TOML | 拍平结果与等价点分键集一致 |
| SPEC-TOML-TST-002 | 值为 `'D:\docs'` 字面量字符串 | 归一化后反斜杠原样保留 |
| SPEC-TOML-TST-003 | 布尔/整数/浮点/字符串数组值 | 按 SPEC-TOML-FMT-003a/b 归一化（数组逗号拼接） |
| SPEC-TOML-TST-004 | `PUT .../raw` 提交非法 TOML 语法 | 400，错误含行/列，磁盘未变 |
| SPEC-TOML-TST-005 | `PUT .../raw` 已知键类型不可解析（`aw.port = "abc"`） | 400，列出违规键与期望类型，磁盘未变 |
| SPEC-TOML-TST-006 | `PUT .../raw` 合法文本 → `GET .../raw` | round-trip 逐字符一致（注释、顺序保真） |
| SPEC-TOML-TST-007 | `PUT .../raw` 修改重启键 / 含未知键 | `restartRequired`/`unknownKeys` 语义与 `SPEC-CFGUI-API-002c` 一致 |
| SPEC-TOML-TST-008 | 启动时存在 `config.properties`、无 `config.toml` | 自动转换生成 TOML，旧文件重命名 `.bak`，创建迁移快照 |
| SPEC-TOML-TST-009 | 启动时 `config.toml` 已存在（无论 properties 是否残留） | 不触发迁移，properties 被忽略 |
| SPEC-TOML-TST-010 | 迁移源文件损坏不可解析 | 不写 TOML、不重命名，记日志，启动不中断 |
| SPEC-TOML-TST-011 | 中文值（路径/模型名）迁移 + TOML round-trip | UTF-8 无乱码 |
| SPEC-TOML-TST-012 | `GET .../raw`，文件不存在 | `text` 为 TOML 模板，含分区表头与路径写法指引 |
| SPEC-TOML-TST-013 | 历史列表含迁移前旧快照 | 旧条目 `format="properties"`，可查看、切换禁用（手动/UI） |
| SPEC-TOML-TST-014 | `ConfigTools.setConfigValue` 写入后读回 | 值落入 `config.toml`，白名单/重启提示行为不变 |
| SPEC-TOML-TST-015 | 结构化 `PUT /desktop/config` 保存 | 重新生成的 `config.toml` 可被 raw 端点与 `Config.load()` 一致解析 |
| SPEC-TOML-TST-016 | 编辑器内测试 LLM 连接，键写在 `[llm]` 表内（手动/UI） | 正确解析出 base-url/model/api-key 并发起测试 |
| SPEC-TOML-TST-017 | `GET .../raw` 响应含 `supportedKeys`，逐项 `assignment` 可被 `parseAndFlatten` 还原为对应默认值 | `supportedKeys` 覆盖全部受支持键，顺序与 `SupportedKeys` 一致 |
| SPEC-TOML-TST-018 | 参考面板「插入」某键 / 已存在键（手动/UI） | 未存在键插入为顶层点分赋值并置脏；已存在键的插入禁用、标注「已配置」 |

---

## 规格追溯矩阵

| 规格 ID | 目标文件/组件 | 验证方式 |
|---------|--------------|---------|
| SPEC-TOML-DEC-001..007 | 本 spec（设计决策）；`config/TomlSupport.java`（DEC-003/004/007）、`config/Config.java`（DEC-001）、`desktop/store/ConfigMigration.java`（DEC-005） | 代码审查 |
| SPEC-TOML-FMT-001..003 | `config/TomlSupport.java`（解析/拍平/归一化/类型校验）、`config/SupportedKeys.java`、`config/Config.java` | 单元测试 `TomlSupportTest`、`ConfigTest` |
| SPEC-TOML-FMT-004 | `config/TomlSupport.java#buildTemplate`、`desktop/controller/DesktopConfigController.java#buildTemplate` | 单元测试 `TomlSupportTest`、`DesktopConfigControllerTest` + 代码审查 |
| SPEC-TOML-MIG-001 | `desktop/store/ConfigMigration.java`、`AppSession.java`（时机）、`desktop/store/ConfigHistoryStore.java`（迁移快照） | 单元测试 `ConfigMigrationTest` |
| SPEC-TOML-MIG-002 | `config/Config.java#overlayUserConfig`、`desktop/store/UserConfigStore.java#loadUser` | 单元测试 `ConfigTest`、`UserConfigStoreRawTest` |
| SPEC-TOML-API-001 | `desktop/controller/DesktopConfigController.java`、`config/TomlSupport.java`、`desktop/store/UserConfigStore.java` | 单元测试 `DesktopConfigControllerTest` |
| SPEC-TOML-API-001e | `desktop/controller/DesktopConfigController.java#supportedKeyInfos`、`config/TomlSupport.java#emitAssignment` | 单元测试 `DesktopConfigControllerTest` |
| SPEC-TOML-API-002 | `desktop/controller/DesktopConfigController.java`、`desktop/store/UserConfigStore.java`、`ConfigTools`（`agent/tools`） | 单元测试 `DesktopConfigControllerTest` + 集成 `AppVerification`（config round trip） |
| SPEC-TOML-UI-001..003 | `desktop-ui/config.js`（`parseEditorToml`、标签、旧格式徽标）、`api.js`、`styles.css` | 静态检查 `check-desktop-config-editor.ps1` + 手动/验收测试 |
| SPEC-TOML-UI-004 | `desktop-ui/config.js`（`renderSupportedKeysPanel`、`insertSupportedKey`、`toggleSupportedKeys`）、`events.js`、`state.js`、`ui.js`、`styles.css` | 手动/验收测试 + 代码审查 |
| SPEC-TOML-VER-001..003 | `desktop/store/ConfigHistoryStore.java`（`format` 字段/脱敏）、`desktop/controller/DesktopConfigController.java`、`desktop-ui/config.js` | 单元测试 `ConfigHistoryStoreTest` + 手动/验收测试 |
| SPEC-TOML-NON-001..005 | 全特性（`SupportedKeys` 键集不变、`Config.java` 保留 classpath/legacy properties、结构化写入丢注释、无回滚工具） | 代码审查 |
| SPEC-TOML-TST-001..016 | 测试用例 | 单元测试 `TomlSupportTest`/`ConfigTest`/`UserConfigStoreRawTest`/`ConfigMigrationTest`/`DesktopConfigControllerTest`/`ConfigHistoryStoreTest` + 手动/验收测试 |
