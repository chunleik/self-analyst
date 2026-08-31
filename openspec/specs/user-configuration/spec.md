# 用户配置与桌面编辑器规格

## Purpose

定义便携包 UTF-8 TOML 用户配置的路径、点分键映射、类型校验、加载优先级、raw 文本编辑器、结构化兼容接口和桌面端口握手行为。

## Requirements

### Requirement: SPEC-TOML-DEC-001..004、007 配置文件与两种写入语义
用户覆盖文件 SHALL 固定为进程工作目录下 `./data/config/config.toml`，使用 TOML v1.0 和 UTF-8；
classpath application.properties SHALL 继续提供打包默认值。内部消费方 SHALL 继续使用拍平点分键和
字符串属性值。raw 写入 SHALL 逐字保存完整文本；结构化端点和 ConfigTools SHALL 重新生成整个 TOML，
不承诺保留注释。写入 SHALL 使用同目录临时文件后 REPLACE_EXISTING；当前不承诺崩溃级 ATOMIC_MOVE。

#### Scenario: raw 逐字往返
- **WHEN** 用户保存包含注释、空行、键顺序和中文路径的合法 TOML
- **THEN** 再次 raw 读取与提交文本逐字符一致

#### Scenario: 结构化单键写入
- **WHEN** ConfigTools 或结构化端点修改一个点分键
- **THEN** 系统重新生成可解析 TOML，值生效但用户原注释不保证保留

### Requirement: SPEC-TOML-GOAL-001..006 TOML 配置目标
用户覆盖 SHALL 以 UTF-8 TOML 持久化；Windows 反斜杠路径 SHALL 可通过字面量字符串所见即所得。
配置路径 MUST 与 memory.dir 解耦，使 TOML 中 memory.dir 在启动时生效。raw 编辑、连接测试、白名单
与重启键 SHALL 继续使用既有点分命名空间。非法 TOML、结构或已知键类型 MUST NOT 落盘，错误 SHALL
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
配置入口 SHALL 打开单个等宽、多行、可滚动纯文本编辑器，不展示结构化字段表单。打开时 SHALL 读取
raw API；文件缺失或为空时 SHALL 显示完整注释模板但不自动写盘。加载失败 SHALL 使编辑器只读。
脏状态 SHALL 按当前文本与最近成功基准逐字符比较；放弃恢复基准，保存期间禁止重复提交。保存成功
SHALL 更新基准并显示 restartRequired/unknownKeys；失败 SHALL 保留脏态和文本。存在未保存修改时关闭
SHALL 先确认。LLM/Embedding 连接测试 SHALL 使用编辑器当前 TOML，缺失密钥可按后端语义回退有效配置。

#### Scenario: 打开空配置
- **WHEN** config.toml 不存在或为空
- **THEN** 编辑器显示合法注释模板，exists/path 状态准确，磁盘仍不创建文件

#### Scenario: 保存校验失败
- **WHEN** 当前文本有 TOML 语法或类型错误
- **THEN** 页面显示完整错误并保留编辑文本、脏态与旧磁盘文件

#### Scenario: 关闭脏编辑器
- **WHEN** 当前文本不同于基准且用户点击关闭或遮罩
- **THEN** 页面先确认，不静默丢弃修改

### Requirement: SPEC-CFGUI-API-001..003 raw 与兼容 API
`GET /desktop/config/raw` SHALL 返回 text、path、exists；文件缺失/空时 text 为模板。`PUT` SHALL 在写盘
前完成 TOML 语法、结构、已知键类型和文件过滤语义校验；通过后逐字替换文件，响应 SHALL 包含 saved、
restartRequired、unknownKeys。任何校验或写入失败 MUST NOT 替换目标或报告成功。结构化
`GET/PUT /desktop/config`、LLM/Embedding 测试端点和 ConfigTools SHALL 保持兼容并使用同一存储。

#### Scenario: raw 保存未知键
- **WHEN** TOML 合法但包含未知点分键
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
- **WHEN** 文档包含 `[aw.collection]` 下的 `window = false`
- **THEN** 内部覆盖键为 `aw.collection.window=false`

#### Scenario: 重复定义
- **WHEN** 同一有效点分键由表内和顶层写法重复定义
- **THEN** 保存返回 400，目标文件不变

### Requirement: SPEC-TOML-FMT-003 值归一化、类型与文件过滤校验
字符串 SHALL 原样归一，boolean 和数字 SHALL 转成十进制字符串；同类基本数组 SHALL 转成逗号列表。
内联表、日期时间、混合数组及嵌套复杂值 MUST 被拒绝。已知键 SHALL 接受声明类型本身或可无损解析的
字符串，类型不匹配时整次拒绝；未知键 MAY 归一化并只警告。文件过滤扩展名、目录名、glob 与最大大小
SHALL 额外执行 fail-closed 语义校验，非法值不得静默放宽采集。

#### Scenario: 基本数组
- **WHEN** `file.watch.extensions` 是字符串数组 `['md','txt']`
- **THEN** 内部值归一为 `md,txt`

#### Scenario: 已知键类型错误
- **WHEN** `aw.port='abc'` 或 `llm.temperature=true`
- **THEN** 保存被整体拒绝并报告违规键与期望类型

### Requirement: SPEC-TOML-FMT-004 双语注释模板
空文件模板 SHALL 按功能表组织全部受支持键，以注释形式提供默认值、声明类型及中文和 English 说明。
模板本身 MUST 是合法 TOML 且不产生覆盖。模板 SHALL 指导 Windows 路径使用单引号字面量或正斜杠。

#### Scenario: 模板可解析
- **WHEN** 后端生成完整配置模板
- **THEN** 解析成功、用户覆盖集为空，所有受支持键均有双语说明

### Requirement: SPEC-TOML-LOAD-001 便携配置路径
后端 SHALL 把进程工作目录视为便携包根，只读写 `./data/config/config.toml`。配置目录 MUST 不依赖
memory.dir；系统 SHALL 先加载 TOML 再解析有效 memory.dir。当前版本 MUST NOT 自动读取或迁移旧
config.properties、memoryDir/config.toml 或用户主目录旧 properties 路径。

#### Scenario: TOML 覆盖 memory.dir
- **WHEN** config.toml 设置新的 memory.dir
- **THEN** 本次启动后续存储使用该目录，而 config.toml 自身路径保持不变

### Requirement: SPEC-TOML-LOAD-002 加载优先级
配置 SHALL 按支持入口的环境变量或 JVM property、用户 TOML、classpath defaults、硬编码默认值顺序
解析。Config 与 UserConfigStore 对同一 TOML SHALL 产生一致拍平结果。aw.port MUST 只由用户 TOML 或
默认值决定，不接受环境变量覆盖。

#### Scenario: 环境变量覆盖模型
- **WHEN** 同一支持键同时存在环境变量和 TOML 值
- **THEN** 环境变量生效

#### Scenario: aw.port 环境变量
- **WHEN** 环境变量尝试设置 aw.port 而 TOML 另有值
- **THEN** Java 使用 TOML/default 端口，不接受环境覆盖

### Requirement: SPEC-TOML-PORT-001 桌面端口握手
Java SHALL 是 aw.port 的唯一解析者，并在监听和认证生命周期路由就绪后原子发布 `1..65535` 的十进制
端口到本次唯一握手文件。Tauri SHALL 等待该文件，并用端口执行健康检查、WebView、浏览器链接和退出；
消费和退出时 SHALL 清理文件。内嵌 AW base URL SHALL 由有效端口派生。端口发布和健康检查各最多等待
30 秒；失败 MUST 退出，不回退硬编码端口。

#### Scenario: 握手成功
- **WHEN** Java 发布合法端口且 token 健康检查成功
- **THEN** 桌面所有入口使用同一端口，握手文件被清理

### Requirement: SPEC-TOML-API-001 raw TOML 端点
raw GET SHALL 在 text/path/exists 基础上返回 SupportedKeys 顺序的 supportedKeys，每项包含点分 key、
小写 type 和默认值 TOML assignment。raw PUT SHALL 返回带行列原因的语法错误，并执行已知键类型与
结构校验。保存成功后原文 SHALL 逐字符 round-trip，一并返回 restartRequired 和 unknownKeys。

#### Scenario: supportedKeys 兼容元数据
- **WHEN** 客户端请求 raw 配置
- **THEN** 响应同时包含原文和后端声明的只读键元数据，UI 不必依赖它才能编辑

### Requirement: SPEC-TOML-API-002 结构化端点与 ConfigTools
结构化配置端点和 ConfigTools SHALL 继续使用点分键、后端白名单、重启提示及同一 config.toml。
LLM 与 Embedding 连接测试端点 SHALL 保持可用。结构化写入 MAY 重排并重生成 TOML，但 MUST 产生与
输入 flat map 等价的可解析结果。

#### Scenario: Agent 修改配置
- **WHEN** ConfigTools 设置允许键
- **THEN** 值写入 config.toml，并按重启键集合返回准确提示

### Requirement: SPEC-TOML-UI-001..004 TOML 编辑器呈现
桌面配置模态 SHALL 直接显示 config.toml 纯文本和实际路径，不显示“全部可配置项”逐项插入面板。
模板打开本身不写盘，只有取消注释或编辑进入脏态。400 错误的行列和违规键 SHALL 完整显示且不清脏态。
连接测试 SHALL 从当前编辑文本解析 llm/embedding 配置，同时支持表内和顶层点分写法。

#### Scenario: 编辑器当前文本连接测试
- **WHEN** 用户修改但尚未保存 llm 或 embedding 配置并点击测试
- **THEN** 测试使用当前 TOML 文本解析值，而不是仅使用磁盘旧值

### Requirement: SPEC-TOML-NON-001..005 配置功能边界
classpath application.properties SHALL 继续作为打包默认值；当前迁移 MUST NOT 新增、删除或重命名
点分配置键。raw 编辑器不承诺语法高亮、补全或行内诊断；结构化写入不承诺注释保真。系统 MUST NOT
提供旧配置路径自动迁移、回滚或已移除的配置历史功能。

#### Scenario: 旧 properties 文件存在
- **WHEN** 旧用户配置路径仍有 properties 文件
- **THEN** 当前启动不自动读取、迁移或删除该文件
