# 用户配置与桌面编辑器规格

## Purpose

定义便携包 UTF-8 TOML 用户配置的路径、点分键映射、类型校验、加载优先级、raw 文本编辑器、结构化兼容接口和桌面端口握手行为。

## Requirements

### Requirement: SPEC-TOML-DEC-001..004、007 配置文件与两种写入语义
用户覆盖文件 SHALL 固定为进程工作目录下 `./data/config/config.toml`，使用 TOML v1.0 和 UTF-8；
classpath application.properties SHALL 继续提供打包默认值。内部消费方 SHALL 继续使用拍平点分键和
字符串属性值。raw 写入 SHALL 逐字保存完整文本；原有通用结构化端点和 ConfigTools SHALL 重新生成整个 TOML，
不承诺保留注释。独立模型设置专用保存 SHALL 按 SPEC-LLMSET-004 定向修改目标键并保留未修改原文，不沿用整文件重生成语义。
写入 SHALL 使用同目录临时文件后 REPLACE_EXISTING；当前不承诺崩溃级 ATOMIC_MOVE。

#### Scenario: raw 逐字往返
- **WHEN** 用户保存包含注释、空行、键顺序和中文路径的合法 TOML
- **THEN** 再次 raw 读取与提交文本逐字符一致

#### Scenario: 结构化单键写入
- **WHEN** ConfigTools 或原有通用结构化端点修改一个点分键
- **THEN** 系统重新生成可解析 TOML，值生效但用户原注释不保证保留

#### Scenario: 模型表单定向写入
- **WHEN** 独立模型设置修改单个模型参数
- **THEN** 原有非目标配置和注释保留，所有保存入口仍使用同一配置文件及运行提交协调

### Requirement: SPEC-TOML-GOAL-001..006 TOML 配置目标
用户覆盖 SHALL 以 UTF-8 TOML 持久化；Windows 反斜杠路径 SHALL 可通过字面量字符串所见即所得。
配置路径 MUST 与 memory.dir 解耦，使 TOML 中 memory.dir 在启动时生效。raw 编辑、连接测试、白名单
与重启键 SHALL 使用当前受支持的点分命名空间。非法 TOML、结构或已知键类型 MUST NOT 落盘，错误 SHALL
提供可读原因和可用行列信息。

#### Scenario: Windows 字面量路径
- **WHEN** 用户配置 `memory.dir = 'D:\docs\中文'`
- **THEN** 解析结果保留反斜杠和中文字符，不发生 properties 转义损坏

### Requirement: SPEC-CFGUI-DEC-001..005 raw 编辑器安全边界
配置编辑器 SHALL 展示用户覆盖原文，而不是合并默认值后的有效配置。raw text MUST 不脱敏，以避免
保存占位符破坏密钥；接口 SHALL 继续受回环 Host/Origin 和桌面 token/cookie 边界保护。保存 SHALL
提交完整文本并使用 UTF-8 临时文件替换。受支持键与需重启键 MUST 由后端单一声明，UI 和 Agent 工具
不得维护独立列表。

#### Scenario: 配置包含真实 API key
- **WHEN** 已认证本地用户打开 raw 编辑器
- **THEN** 编辑器显示真实配置文本；未通过回环和桌面认证的请求不能读取该文本

### Requirement: SPEC-CFGUI-UI-001..005 配置编辑器交互
配置入口 SHALL 默认打开独立的“模型设置”结构化视图，并提供“高级配置”原文视图；打开时只加载所选视图所需数据，不自动读取 raw 密钥文本到模型表单。
高级配置 SHALL 展示单个等宽、多行、可滚动纯文本编辑器，进入时 SHALL 读取 raw API；文件缺失或为空时 SHALL 显示完整注释模板但不自动写盘。加载失败 SHALL 使编辑器只读。
原文脏状态 SHALL 按当前文本与最近成功基准逐字符比较；放弃恢复基准，保存期间禁止重复提交。保存成功
SHALL 更新基准并显示 restartRequired/unknownKeys 及各组件的运行时应用结果；未保存成功时 SHALL
保留脏态和文本。不可用状态或仍有旧版本任务时 MUST 给出准确说明，不将“已保存”解释为全部组件已切换。
来源与运行状态 SHALL 作为只读辅助信息呈现，不替换原文编辑器。任一视图存在未保存修改时关闭或切换
SHALL 先确认；取消确认保留视图和草稿，确认放弃后进入目标视图并读取最新已保存配置，MUST NOT 静默互相覆盖。
原文视图 LLM/Embedding 连接检查 SHALL 使用编辑器当前 TOML，缺失密钥可按后端语义回退有效配置；新模型设置的生成测试遵循 SPEC-LLMSET-006。

#### Scenario: 打开空配置
- **WHEN** config.toml 不存在或为空且用户进入高级配置
- **THEN** 编辑器显示合法注释模板，exists/path 状态准确，磁盘仍不创建文件

#### Scenario: 保存校验失败
- **WHEN** 当前文本有 TOML 语法或类型错误
- **THEN** 页面显示完整错误并保留编辑文本、脏态与旧磁盘文件

#### Scenario: 关闭脏编辑器
- **WHEN** 当前文本不同于基准且用户点击关闭或遮罩
- **THEN** 页面先确认，不静默丢弃修改

#### Scenario: 运行中修改模型
- **WHEN** 当前回答尚未结束且用户成功保存新的 LLM 模型配置
- **THEN** 编辑器显示保存成功、新一轮采用新配置及当前回答继续使用旧配置；不得提示全部请求已切换

#### Scenario: 未配置有效密钥
- **WHEN** 保存后有效 LLM 密钥为空或占位值
- **THEN** 界面更新已保存基准并显示 LLM 不可用及配置指引，不显示连接成功

#### Scenario: 从脏原文切换模型设置
- **WHEN** 原文有未保存修改且用户确认放弃后切换模型设置
- **THEN** 模型表单从已保存配置重新读取，不使用被放弃原文构建表单

### Requirement: SPEC-CFGUI-API-001..003 raw 与兼容 API
`GET /desktop/config/raw` SHALL 返回 text、path、exists；文件缺失/空时 text 为模板。`PUT` SHALL 在写盘
前完成 TOML 语法、结构、已知键类型和文件过滤语义校验；通过后逐字替换文件，响应 SHALL 包含 saved、
restartRequired、unknownKeys，并 SHALL 附带保存版本和运行时应用结果。任何校验、候选运行资源准备或写入失败 MUST NOT 替换目标或报告成功。结构化
`GET/PUT /desktop/config`、LLM/Embedding 测试端点和 ConfigTools SHALL 继续使用同一存储；本次事件配置键及结构化事件分组的改名遵循 SPEC-TOML-RENAME-001、002，不提供旧名称兼容。

#### Scenario: raw 保存未知键
- **WHEN** TOML 合法但包含普通未知点分键，且没有本次移除的已知旧名称
- **THEN** 文本可保存，响应在 unknownKeys 中报告该键

#### Scenario: raw 写入失败
- **WHEN** 临时文件或替换目标失败
- **THEN** 原配置保持不变，API 返回错误且 saved 不为 true

### Requirement: SPEC-TOML-FMT-001 文件与覆盖项
config.toml SHALL 只包含用户覆盖，不把打包默认值固化进文件。文件 SHALL 按 UTF-8 TOML v1.0 解析。

#### Scenario: 仅有注释模板
- **WHEN** 模板中所有配置赋值均被注释
- **THEN** 解析产生空用户覆盖集，运行时继续使用默认值

### Requirement: SPEC-TOML-FMT-002 表与点分键拍平
解析器 SHALL 把表路径和键以 `.` 连接为内部点分键；表内写法和顶层点分写法 SHALL 等价。TOML 对
同一键的重复定义 MUST 作为解析错误，不采用后者覆盖。

#### Scenario: 嵌套表拍平
- **WHEN** 文档包含 `[events.collection]` 下的 `window = false`
- **THEN** 内部覆盖键为 `events.collection.window=false`

#### Scenario: 重复定义
- **WHEN** 同一有效点分键由表内和顶层写法重复定义
- **THEN** 保存返回 400，目标文件不变

### Requirement: SPEC-TOML-FMT-003 值归一化、类型与文件过滤校验
字符串 SHALL 原样归一，boolean 和数字 SHALL 转成十进制字符串；同类基本数组 SHALL 转成逗号列表。
内联表、日期时间、混合数组及嵌套复杂值 MUST 被拒绝。已知键 SHALL 接受声明类型本身或可无损解析的
字符串，类型不匹配时整次拒绝；普通未知键 MAY 归一化并只警告；本次移除的已知旧名称 MUST 按 SPEC-TOML-RENAME-002 拒绝。文件过滤扩展名、目录名、glob 与最大大小
SHALL 额外执行 fail-closed 语义校验，非法值不得静默放宽采集。

#### Scenario: 基本数组
- **WHEN** `file.watch.extensions` 是字符串数组 `['md','txt']`
- **THEN** 内部值归一为 `md,txt`

#### Scenario: 已知键类型错误
- **WHEN** `events.port='abc'` 或 `llm.temperature=true`
- **THEN** 保存被整体拒绝并报告违规键与期望类型

### Requirement: SPEC-TOML-FMT-004 双语注释模板
空文件模板 SHALL 按功能表组织全部受支持键，以注释形式提供默认值、声明类型及中文和 English 说明。
模板本身 MUST 是合法 TOML 且不产生覆盖。模板 SHALL 指导 Windows 路径使用单引号字面量或正斜杠。
`events.mode`、`events.port`、`events.base-url`、`events.timeout`、`events.data-dir` 的双语说明 SHALL 使用事件服务用语，
MUST NOT 使用 ActivityWatch 品牌名；键名保持 `events.*`。

#### Scenario: 模板可解析
- **WHEN** 后端生成完整配置模板
- **THEN** 解析成功、用户覆盖集为空，所有受支持键均有双语说明

#### Scenario: aw 键说明不含品牌名
- **WHEN** 后端生成完整配置模板
- **THEN** `events.mode`、`events.port`、`events.base-url`、`events.timeout`、`events.data-dir` 的中英注释均不包含
  字符串 ActivityWatch

### Requirement: SPEC-TOML-LOAD-001 便携配置路径
后端 SHALL 把进程工作目录视为便携包根，只读写 `./data/config/config.toml`。配置目录 MUST 不依赖
memory.dir；系统 SHALL 先加载 TOML 再解析有效 memory.dir。当前版本 MUST NOT 自动读取或迁移旧
config.properties、memoryDir/config.toml 或用户主目录旧 properties 路径。

#### Scenario: TOML 覆盖 memory.dir
- **WHEN** config.toml 设置新的 memory.dir
- **THEN** 本次启动后续存储使用该目录，而 config.toml 自身路径保持不变

### Requirement: SPEC-TOML-LOAD-002 加载优先级
配置 SHALL 按用户 TOML 显式值、支持入口的环境变量、classpath defaults、硬编码默认值顺序
解析。未配置或删除用户覆盖后 SHALL 恢复环境变量兜底；显式空值 MUST NOT 被环境变量覆盖，
其合法性与默认值处理 SHALL 保持各键的既有规则。memory.dir 的显式 JVM property SHALL 继续优先于 TOML。
应用启动、有效配置查询、保存差异计算与连接测试 SHALL 复用同一解析规则；用户覆盖原文 SHALL
与解析后的有效配置分开呈现。events.port MUST 只由用户 TOML 或
默认值决定，不接受环境变量覆盖。

#### Scenario: 用户配置覆盖环境变量
- **WHEN** 同一支持键同时存在环境变量和 TOML 值
- **THEN** 用户 TOML 值生效

#### Scenario: 删除覆盖恢复环境变量
- **WHEN** 用户删除一个支持环境变量的 TOML 配置项
- **THEN** 后续加载使用非空环境变量；环境变量未设置时使用内置默认值

#### Scenario: 连接测试与配置加载一致
- **WHEN** 连接测试未显式提供模型连接参数
- **THEN** LLM 与 Embedding 测试使用当前已保存配置按同一优先级解析的参数；未配置独立 Embedding 密钥时沿用有效 LLM 密钥

#### Scenario: aw.port 环境变量
- **WHEN** 环境变量尝试设置 events.port 而 TOML 另有值
- **THEN** Java 使用 TOML/default 端口，不接受环境覆盖

#### Scenario: 仅由环境变量配置模型
- **WHEN** 用户 TOML 未设置模型而环境变量提供模型值
- **THEN** 有效配置查询与 Agent 配置工具显示环境变量模型及其来源，不误显示内置默认值

#### Scenario: 显式恢复为内置默认模型
- **WHEN** 环境变量提供非默认模型，用户通过结构化接口或 Agent 配置工具显式设置内置默认模型
- **THEN** 系统保存该 TOML 覆盖，并使后续有效配置采用用户显式模型，不因其等于内置默认值而忽略写入

#### Scenario: 删除覆盖后的运行配置
- **WHEN** 用户通过应用内保存删除 LLM 配置项并存在对应环境变量
- **THEN** 后续新工作采用环境变量兜底值，应用结果根据实际解析后的变化报告

### Requirement: SPEC-TOML-PORT-001 桌面端口握手
Java SHALL 是 events.port 的唯一解析者，并在监听和认证生命周期路由就绪后原子发布 `1..65535` 的十进制
端口到本次唯一握手文件。Tauri SHALL 等待该文件，并用端口执行健康检查、WebView、浏览器链接和退出；
消费和退出时 SHALL 清理文件。内嵌事件服务 base URL SHALL 由有效端口派生。端口发布和健康检查各最多等待
30 秒；失败 MUST 退出，不回退硬编码端口。

#### Scenario: 握手成功
- **WHEN** Java 发布合法端口且 token 健康检查成功
- **THEN** 桌面所有入口使用同一端口，握手文件被清理

### Requirement: SPEC-TOML-API-001 raw TOML 端点
raw GET SHALL 在 text/path/exists 基础上返回 SupportedKeys 顺序的 supportedKeys，每项包含点分 key、
小写 type 和默认值 TOML assignment。raw PUT SHALL 返回带行列原因的语法错误，并执行已知键类型与
结构校验，并按 SPEC-TOML-RENAME-002 拒绝本次移除的已知旧名称。保存成功后原文 SHALL 逐字符 round-trip，一并返回 restartRequired 和 unknownKeys。

#### Scenario: supportedKeys 兼容元数据
- **WHEN** 客户端请求 raw 配置
- **THEN** 响应同时包含原文和后端声明的只读键元数据，UI 不必依赖它才能编辑

#### Scenario: 新事件键元数据
- **WHEN** 客户端请求 raw 配置或有效配置
- **THEN** 受支持键、有效值、继承来源键和重启差异使用 `events.*`，不把旧名称列为可用配置；raw 用户原文仍逐字呈现

### Requirement: SPEC-TOML-API-002 结构化端点与 ConfigTools
结构化配置端点和 ConfigTools SHALL 继续使用点分键、后端白名单、统一生效策略、应用结果及同一 config.toml。
LLM 与 Embedding 连接测试端点 SHALL 保持可用。结构化写入 MAY 重排并重生成 TOML，但 MUST 产生与
输入 flat map 等价的可解析结果。
结构化配置 SHALL 使用 `events` 事件服务分组及 `collection.title.enabled/pollMs` 标题采集字段；其余结构化配置分组、字段与端点路径保持原有契约。旧 `aw` 分组中的本次移除配置以及 `collection.content`、`collection.content.pollMs` MUST 按 SPEC-TOML-RENAME-002 拒绝，不能静默跳过后报告成功。Agent 配置工具 SHALL 使用新点分键，并保持原有可写项范围，仅将对应旧可写键替换为新键。

#### Scenario: Agent 修改配置
- **WHEN** ConfigTools 设置允许键
- **THEN** 值经统一校验写入 config.toml，并返回新工作生效、需重启或不可用等准确结果

#### Scenario: Agent 在当前轮修改模型
- **WHEN** Agent 配置工具在正在执行的聊天轮次中保存新模型
- **THEN** 保存及时返回，当前轮继续完成，下一轮采用新模型，不等待当前轮结束而产生自锁

#### Scenario: 三种保存入口拒绝非法模型参数
- **WHEN** raw、结构化或 Agent 工具提交类型错误、非有限或超出 0..2 的温度、负输出上限或不可用作 HTTP(S) 基础地址的值
- **THEN** 系统按相同规则拒绝，保留原文件和当前运行配置；缺失或空密钥按未配置状态处理

#### Scenario: 结构化新采集配置
- **WHEN** 当前事件服务以默认标题配置运行，客户端提交 `{"collection":{"title":{"enabled":false,"pollMs":800}}}`
- **THEN** 保存 `events.collection.title.enabled=false` 和 `events.collection.title.pollMs=800`，输出 TOML 可解析，响应准确报告事件服务组件需重启

#### Scenario: Agent 使用已移除键
- **WHEN** Agent 配置工具尝试设置 `aw.collection.content`
- **THEN** 工具返回该名称已移除及应使用 `events.collection.title.enabled` 的错误，不写入配置、不报告成功

### Requirement: SPEC-TOML-UI-001..004 TOML 编辑器呈现
桌面配置模态的“高级配置”视图 SHALL 直接显示 config.toml 纯文本和实际路径，不显示“全部可配置项”逐项插入面板。
模板打开本身不写盘，只有取消注释或编辑进入脏态。400 错误的行列和违规键 SHALL 完整显示且不清脏态。
原文连接检查 SHALL 从当前编辑文本解析 llm/embedding 配置，同时支持表内和顶层点分写法。
来自其它功能的配置键定位入口 SHALL 直接打开高级配置并定位对应键，同时遵循未保存草稿确认规则。

#### Scenario: 编辑器当前文本连接测试
- **WHEN** 用户修改但尚未保存 llm 或 embedding 配置并点击原文连接检查
- **THEN** 检查使用当前 TOML 文本解析值，而不是仅使用磁盘旧值

#### Scenario: 其它功能定位配置键
- **WHEN** 用户从文件设置指引请求定位一个配置键
- **THEN** 配置窗口进入高级配置并保留现有键定位能力，不停留在模型表单

### Requirement: SPEC-TOML-NON-001..005 配置功能边界
classpath application.properties SHALL 继续作为打包默认值；事件配置 SHALL 按 SPEC-TOML-RENAME-001 一次性改名并移除列明的旧名称；其它既有点分配置键 MUST 保持不变，
新增能力 MAY 通过明确规格定义的受支持键扩展配置集合。raw 编辑器不承诺语法高亮、补全或行内诊断；
结构化写入不承诺注释保真。系统 MUST NOT 提供旧配置路径自动迁移、回滚或已移除的配置历史功能。

#### Scenario: 旧 properties 文件存在
- **WHEN** 旧用户配置路径仍有 properties 文件
- **THEN** 当前启动不自动读取、迁移或删除该文件

### Requirement: SPEC-TOML-RAW-001 原始事件配置集合
系统 SHALL 支持 `events.raw.dir`、`events.raw.query.maxRangeDays`、`events.raw.query.maxPageSize`、`events.raw.lowDisk.warnBytes`、`events.raw.lowDisk.blockBytes`、`events.raw.integrity.startupScope` 和 `events.raw.projector.batchSize`。原始事件能力在嵌入式模式 SHALL 固定启用，MUST NOT 提供关闭永久保存、按天保留或自动删除分区的普通用户配置。

#### Scenario: 默认原始事件配置
- **WHEN** 用户没有覆盖原始事件配置
- **THEN** 原始目录派生为 `{events.data-dir}/raw`，使用月度分区、31 天查询范围上限、1000 条单页上限、10 GiB 告警阈值、1 GiB 阻断阈值、启动校验最新分区和 1000 条投影批量大小

#### Scenario: 禁止关闭永久保存
- **WHEN** 用户尝试配置原始事件保留天数、最大分区数、自动删除或关闭嵌入式原始事件层
- **THEN** 系统不把这些键作为受支持配置，也不因此删除或停止记录合规原始事件

### Requirement: SPEC-TOML-RAW-002 原始事件配置校验
原始目录 SHALL 接受可解析的本地路径；查询范围、页大小、磁盘阈值和投影批量大小 MUST 为正整数并受后端声明上限约束；阻断阈值 MUST 小于告警阈值；启动完整性策略 SHALL 只接受 `latest` 或 `all`。任一已知原始配置无效时，整次 raw TOML 保存 MUST 被拒绝且原文件保持不变。

#### Scenario: 磁盘阈值关系错误
- **WHEN** `events.raw.lowDisk.blockBytes` 大于或等于 `events.raw.lowDisk.warnBytes`
- **THEN** 配置保存返回带违规键的校验错误，磁盘上的 TOML 不变

#### Scenario: 无效完整性策略
- **WHEN** `events.raw.integrity.startupScope` 不是 `latest` 或 `all`
- **THEN** 配置保存和启动配置解析拒绝该值，不静默关闭完整性校验

### Requirement: SPEC-TOML-RAW-003 原始目录变更边界
尚未产生原始事件时，系统 MAY 接受 `events.raw.dir` 变更；已经存在任何原始分区后，普通配置保存 MUST 拒绝改变有效原始目录，并说明需要独立的显式转存流程。配置修改或路径不可用 MUST NOT 导致系统遗忘或自动删除旧分区。

#### Scenario: 有数据后修改原始目录
- **WHEN** 原始 catalog 已记录至少一个分区且用户修改 `events.raw.dir`
- **THEN** 系统拒绝该配置变更，原配置和全部原始分区保持不变

### Requirement: SPEC-CFG-LIVE-001 LLM 应用范围与组件差异
应用内保存 SHALL 支持 llm.api-key、llm.base-url、llm.model、llm.temperature 和 llm.max-tokens
对新工作生效。温度变更 SHALL 影响聊天模型，plain/summary 与压缩 SHALL 保持既有低温策略。
系统 SHALL 按解析后的组件配置比较差异；只有注释或来源变化而有效模型参数相同时 MUST NOT 重建模型。
非热更新组件的重启提示 SHALL 对比其实际运行配置与最新已保存配置，恢复原值后 SHALL 清除该待重启提示。
配置生效策略 SHALL 由后端集中声明，桌面接口和 Agent 工具 MUST 使用同一声明。

#### Scenario: 同时保存模型与端口
- **WHEN** 一次保存同时修改 LLM 模型与服务端口
- **THEN** 新 LLM 工作采用新模型，端口保持当前监听值并列为需重启，不因重建模型提前应用端口或其它启动期配置

#### Scenario: 仅修改注释
- **WHEN** 用户只修改 TOML 注释或空行后保存
- **THEN** 原文逐字保存，模型运行版本不变化，不产生额外连接或重启提示

#### Scenario: 恢复待重启配置
- **WHEN** 用户将尚未生效的启动期配置改回当前运行值
- **THEN** 对应待重启提示消失，其它尚未生效的组件提示继续保留

#### Scenario: Embedding 继承密钥变化
- **WHEN** 已运行的 Embedding 客户端继承 LLM 密钥，用户改变有效 LLM 密钥
- **THEN** 新 LLM 工作采用新密钥，Embedding 继续使用当前运行配置，结果明确指出 Embedding 密钥差异需重启；未运行的 Embedding MUST NOT 被自动启用

#### Scenario: 外部编辑文件
- **WHEN** 用户仅通过外部编辑器修改 config.toml，未经过应用保存入口
- **THEN** 本期不保证运行时自动切换；后续启动或应用内保存 SHALL 重新解析当前文件

### Requirement: SPEC-CFG-LIVE-002 有效配置和应用结果可见性
系统 SHALL 提供受既有桌面认证保护的 GET /desktop/config/effective，区分最新已保存配置及来源、
新工作将使用的 LLM 版本、尚在使用旧版本的工作，以及其它组件待重启差异。来源 SHALL 区分 TOML、
环境变量、JVM property、默认值及继承值。普通配置查询、运行状态、日志及错误中的密钥 MUST 脱敏，
MUST NOT 输出密钥派生的可比对摘要；已认证 raw 原文编辑的既有边界保持不变。
保存响应 SHALL 保留兼容字段，并提供保存版本、LLM 运行版本和组件应用结果；saved=true 只表示
本次持久化成功，MUST NOT 作为远端模型可调用或全部组件已切换的证明。
应用结果 SHALL 区分已应用、仍有旧版本工作、需重启和未配置不可用；应用失败 SHALL 通过失败响应报告。

#### Scenario: 新旧版本同时存在
- **WHEN** 保存成功后一个旧版本回答仍在运行
- **THEN** 查询显示新工作采用的版本及仍有旧版本工作；旧工作结束后相应状态消失

#### Scenario: 重新打开配置
- **WHEN** 用户关闭后重新打开配置编辑器
- **THEN** 仍可看到当前生效来源及尚未生效的组件，不依赖上一条保存提示

#### Scenario: 非 raw 查询密钥
- **WHEN** 已认证客户端读取有效配置，或发生包含密钥的底层异常
- **THEN** 响应和日志只提供脱敏值、来源及可操作错误，不暴露密钥或原始认证请求

### Requirement: SPEC-CFG-LIVE-003 保存失败与并发一致性
配置更新 SHALL 协调校验、候选运行资源准备、持久化和版本发布。校验、候选资源准备或写盘失败时
MUST 保持原磁盘文件和当前可用运行版本，并释放候选资源。保存的本地成功 MUST NOT 依赖额外远端
连接测试；新模型后续返回认证、限流或网络错误时 SHALL 报告实际错误，MUST NOT 自动回退到旧供应商或密钥。
同进程配置写入 SHALL 串行提交，后提交的版本 MUST NOT 被较早构建的候选覆盖。关闭开始后 SHALL 拒绝
新保存且不留下已落盘但未发布的普通成功响应。本期不新增配置历史、自动恢复旧文件或跨进程事务承诺。

#### Scenario: 构建新运行资源失败
- **WHEN** 新模型运行资源无法完成本地构建
- **THEN** 保存返回失败，原配置文件和旧模型仍可使用，错误不泄露密钥

#### Scenario: 写入失败
- **WHEN** 候选资源已准备完成但配置文件替换失败
- **THEN** 保存返回失败并释放候选资源，旧文件和旧运行版本保持不变

#### Scenario: 连续提交两个模型
- **WHEN** 两个保存请求依次被接受并成功提交不同模型
- **THEN** 新工作使用后一次提交的模型，返回的版本与实际提交顺序一致

#### Scenario: 新模型返回认证失败
- **WHEN** 保存成功后新模型在实际调用中返回认证错误
- **THEN** 系统报告该调用失败，不把请求发送给旧供应商，不静默修改文件

#### Scenario: 关闭期间保存
- **WHEN** 应用已开始关闭后收到配置保存请求
- **THEN** 请求被拒绝，关闭过程不创建新模型或重启后台服务

### Requirement: SPEC-TOML-RENAME-001 事件服务配置命名

系统 SHALL 以下表新名称作为事件配置的唯一受支持入口；旧名称只用于拒绝诊断和手动改名说明，MUST NOT 作为别名、兜底或自动迁移输入。表中环境变量映射 SHALL 显式生效，MUST NOT 为端口增加环境入口，也 MUST NOT 支持省略 TITLE_ENABLED 或 COLLECTION 的推测别名。

| 旧配置键 | 新配置键 | 旧环境变量 | 新环境变量 |
|---|---|---|---|
| `aw.mode` | `events.mode` | `AW_MODE` | `EVENTS_MODE` |
| `aw.port` | `events.port` | — | — |
| `aw.base-url` | `events.base-url` | `AW_BASE_URL` | `EVENTS_BASE_URL` |
| `aw.timeout` | `events.timeout` | `AW_TIMEOUT` | `EVENTS_TIMEOUT` |
| `aw.data-dir` | `events.data-dir` | `AW_DATA_DIR` | `EVENTS_DATA_DIR` |
| `aw.raw.dir` | `events.raw.dir` | `AW_RAW_DIR` | `EVENTS_RAW_DIR` |
| `aw.raw.query.maxRangeDays` | `events.raw.query.maxRangeDays` | `AW_RAW_QUERY_MAX_RANGE_DAYS` | `EVENTS_RAW_QUERY_MAX_RANGE_DAYS` |
| `aw.raw.query.maxPageSize` | `events.raw.query.maxPageSize` | `AW_RAW_QUERY_MAX_PAGE_SIZE` | `EVENTS_RAW_QUERY_MAX_PAGE_SIZE` |
| `aw.raw.lowDisk.warnBytes` | `events.raw.lowDisk.warnBytes` | `AW_RAW_LOW_DISK_WARN_BYTES` | `EVENTS_RAW_LOW_DISK_WARN_BYTES` |
| `aw.raw.lowDisk.blockBytes` | `events.raw.lowDisk.blockBytes` | `AW_RAW_LOW_DISK_BLOCK_BYTES` | `EVENTS_RAW_LOW_DISK_BLOCK_BYTES` |
| `aw.raw.integrity.verifyOnStartup` | `events.raw.integrity.startupScope` | `AW_RAW_INTEGRITY_VERIFY_ON_STARTUP` | `EVENTS_RAW_INTEGRITY_STARTUP_SCOPE` |
| `aw.raw.projector.batchSize` | `events.raw.projector.batchSize` | `AW_RAW_PROJECTOR_BATCH_SIZE` | `EVENTS_RAW_PROJECTOR_BATCH_SIZE` |
| `aw.collection.window` | `events.collection.window` | `AW_COLLECTION_WINDOW` | `EVENTS_COLLECTION_WINDOW` |
| `aw.collection.afk` | `events.collection.afk` | `AW_COLLECTION_AFK` | `EVENTS_COLLECTION_AFK` |
| `aw.collection.content` | `events.collection.title.enabled` | `AW_COLLECTION_CONTENT` | `EVENTS_COLLECTION_TITLE_ENABLED` |
| `aw.collection.content.pollMs` | `events.collection.title.pollMs` | `AW_CONTENT_POLL_MS` | `EVENTS_COLLECTION_TITLE_POLL_MS` |

各项默认值、声明类型、合法范围、有效值计算、服务模式、事件服务组件归属及重启要求 SHALL 沿用改名前行为。修改配置名称 MUST NOT 改变采集数据范围、HTTP 路径、事件 schema、bucket ID 或文件位置。环境变量兜底与 TOML 显式值的优先级 SHALL 遵循 SPEC-TOML-LOAD-002。

#### Scenario: 仅使用新环境变量
- **WHEN** TOML 未覆盖事件配置，环境变量设置 `EVENTS_MODE=external`、`EVENTS_BASE_URL=http://localhost:5600/api/0`，且没有已移除环境变量
- **THEN** 应用连接该外部地址，有效配置显示新键及环境来源，外部模式的原始事件能力仍为 unavailable

#### Scenario: 新旧输入值的行为对应
- **WHEN** 用户按对照表手动替换全部名称并保留有效值、路径及工作目录
- **THEN** 下次启动使用相同端口、数据目录、采集开关、查询限制、磁盘阈值和校验范围，不因为改名迁移或重建数据

#### Scenario: 端口不接受环境覆盖
- **WHEN** TOML 设置 `events.port=5810`，启动环境另有 `EVENTS_PORT` 或从未受支持的 `AW_PORT`
- **THEN** Java 仍使用 5810，通过握手通知桌面壳，并由该端口派生内嵌事件 API 地址

### Requirement: SPEC-TOML-RENAME-002 已移除名称拒绝与手动更新

启动解析、raw/结构化配置保存、Agent 配置写入及使用配置文本的连接测试 SHALL 检查 SPEC-TOML-RENAME-001 列明的旧键与旧环境变量。只要这些旧名称显式存在，即使值为空、被 TOML 遮蔽或与新名称同时出现，系统 MUST 拒绝该次配置解析或提交，并指出旧名称、对应新名称和手动修改/移除要求；MUST NOT 读取旧值作为有效配置，也 MUST NOT 自动改写输入。

启动失败 SHALL 发生在事件服务、采集器或数据目录初始化之前。raw/结构化 HTTP 配置提交与文本连接测试 SHALL 返回 400；Agent 工具 SHALL 返回明确失败。失败 MUST 保留磁盘文件和现有运行配置。诊断 SHALL 仅展示名称与来源，不包含配置值或完整配置文本。

该规则 SHALL 只针对表内已移除名称及对应结构化字段。普通未知键仍按现有未知键规则处理；旧 OCR 键及 `aw.audio.*` 仍按已移除功能规则接受但忽略，不映射为 `events.*`，不恢复受支持状态。此前禁止的 raw 开关/TTL/容量/删除键在旧前缀和新前缀下 SHALL 均继续拒绝，不能借改名启用保留策略。

#### Scenario: 旧目录配置阻止启动回退
- **WHEN** 启动 TOML 中仍包含 `aw.raw.dir`
- **THEN** 启动报出应改为 `events.raw.dir` 的错误，不使用默认 raw 目录启动采集，不改动配置或原始分区

#### Scenario: 新旧名称并存
- **WHEN** 用户同时提交 `aw.mode` 和 `events.mode`，或进程同时设置 `AW_MODE` 和 `EVENTS_MODE`
- **THEN** 即使两者值相同也拒绝解析，要求移除旧名称，不选择任一名称优先

#### Scenario: 被遮蔽的旧环境变量
- **WHEN** TOML 已配置 `events.collection.title.enabled=false`，启动环境仍定义 `AW_COLLECTION_CONTENT`
- **THEN** 启动拒绝并提示更新环境变量；不因新 TOML 已覆盖而忽略旧名称

#### Scenario: 结构化旧事件分组
- **WHEN** 客户端提交 `{"aw":{"mode":"embedded"}}` 或 `{"collection":{"content":false}}`
- **THEN** 返回 400 及对应新配置名称，原磁盘与运行配置不变

#### Scenario: 旧名称仅出现在注释中
- **WHEN** 合法 TOML 的注释中包含 `aw.mode`，但实际赋值全部使用新名称且环境干净
- **THEN** 允许保存和解析，raw 往返保留原注释

#### Scenario: 已移除采集功能仍被忽略
- **WHEN** 旧配置包含 `aw.ocr.engine` 或 `aw.audio.enabled`，但没有本次改名表中的旧键
- **THEN** 不因这些废弃键触发本次改名错误，仍不启用 OCR 或音频能力，模板与支持键不暴露它们

#### Scenario: 未支持的永久层关闭配置
- **WHEN** 用户提交 `aw.raw.enabled=false` 或 `events.raw.enabled=false`，或在任一前缀下配置 retentionDays、maxPartitions、autoDelete
- **THEN** 系统拒绝这些保留策略配置，不关闭永久记录或删除分区

### Requirement: SPEC-TOML-RENAME-003 标题配置结构与校验范围

标题采集 SHALL 使用 `events.collection.title.enabled` 布尔开关和 `events.collection.title.pollMs` 整数间隔；默认值分别为 true 与 500 毫秒，间隔范围及越界处理 SHALL 保持既有行为。标题采集 SHALL 继续只保存标题事实，不因改名采集正文、控件树、截图、OCR 或音频。启动完整性校验 SHALL 使用 `events.raw.integrity.startupScope`，默认 latest，仅允许 latest/all，MUST NOT 将此枚举解释为布尔开关。

模板、结构化生成和 raw 元数据中的赋值 SHALL 允许同时启用标题开关与轮询间隔，不产生 TOML 标量/表冲突。

#### Scenario: 标题开关与间隔共同保存
- **WHEN** 用户在 `[events.collection.title]` 表内设置 `enabled=false` 和 `pollMs=800`
- **THEN** 两项独立解析为对应新键；结构化保存后再次解析得到相同覆盖值

#### Scenario: 完整性校验全部分区
- **WHEN** 用户设置 `events.raw.integrity.startupScope='all'`
- **THEN** 启动按 SPEC-RAW-013 校验全部已有分区；设置布尔值 true 或字符串 false 则拒绝，不关闭校验
