# 国际化规格

## Purpose

定义中文与英文的有效语言解析、后端 LLM 提示词、桌面 UI 消息目录、配置入口和生效时机，使界面与模型回复使用一致语言而不改变业务数据契约。

## Requirements

### Requirement: SPEC-I18N-DEC-001..004、SPEC-I18N-GOAL-001..006 有效语言与兼容边界
系统当前 SHALL 提供 zh 和 en 两种有效语言。`app.language` SHALL 支持 zh/en/auto，默认 auto；显式支持值直接选择语言，auto、空值或非法值 SHALL 根据系统 Locale 匹配已支持的语言，无法匹配时使用 en。语言代码匹配 SHALL 忽略大小写，区域 Locale SHALL 按基础语言匹配。后端 SHALL 提供唯一有效语言结果，正常运行的桌面 UI、原生壳和提示词 MUST 使用该结果。国际化 MUST NOT 改变既有交互、业务数据、发送或持久化语义；状态和错误响应可增加兼容的语言元数据。

#### Scenario: 中文系统自动解析
- **WHEN** app.language=auto 且系统 Locale 为 zh-CN 或 zh-TW
- **THEN** effective language 为 zh

#### Scenario: 非中文系统自动解析
- **WHEN** app.language=auto 或非法值且系统 Locale 为 en-US 或未支持语言
- **THEN** effective language 为 en，解析不抛错

#### Scenario: 显式覆盖
- **WHEN** 系统 Locale 为英文但 app.language=zh
- **THEN** 后端、页面和就绪后的原生壳使用 zh

### Requirement: SPEC-I18N-DEC-005、SPEC-I18N-PROMPT-001 双语提示词资源
所有发给 LLM 的 Agent 基础/能力片段、纯改写、活动摘要、行为建议和会话摘要提示 SHALL 提供人工维护
的 zh/en 变体，并按有效语言选用。系统 MUST NOT 在运行时调用 LLM 翻译提示词。提示词资源 SHALL 以
UTF-8 随应用构建，修改后需要重新构建并重启。

#### Scenario: English 提示词
- **WHEN** effective language 为 en
- **THEN** Agent、摘要和建议构建器使用英文资源，不拼入要求模型照搬的中文字面量

### Requirement: SPEC-I18N-PROMPT-002、003 以有效语言回复
Agent system prompt SHALL 明确要求以有效语言回复。English 变体 MUST 使用英文身份、能力和固定提示；
中文变体 SHALL 使用中文。会话摘要与确定性包裹文案 SHALL 跟随有效语言，但用户原文 MUST NOT 为语言
统一而被翻译或改写。

#### Scenario: English Agent 回复指令
- **WHEN** effective language 为 en
- **THEN** system prompt 明确要求 English 回复，且不包含固定中文行动短语

#### Scenario: 中文会话摘要
- **WHEN** effective language 为 zh
- **THEN** 会话摘要提示要求中文概括，引用的用户原文保持原样

### Requirement: SPEC-I18N-PROMPT-004、005 日期与兜底文本
提示词中的 current time 和 UTC→本地时间说明 SHALL 按有效语言使用对应 Locale 文本。预算阻断、Wiki
不可用和其它确定性提示 SHALL 跟随有效语言；由用户原文构造的确定性摘要 SHALL 保持用户语言。

#### Scenario: English 时间文本
- **WHEN** effective language 为 en 且 prompt 注入当前时间
- **THEN** 日期和时间说明使用英文 Locale 与 English 文案

### Requirement: SPEC-I18N-DEC-006、SPEC-I18N-UI-001、002 前端消息目录
桌面 UI SHALL 使用稳定 key 和按语言维护的消息目录及 `t(key, params)` 查找函数，资源 SHALL 在业务渲染前就绪，且不引入打包器或框架。查找 SHALL 支持命名参数替换；当前语言缺失时 SHALL 依次回退英文和 key 本身，不能中断渲染。参数值 MUST 作为数据插入，不得作为模板再次解析或作为未转义 HTML 执行。

#### Scenario: 参数化文案
- **WHEN** t() 查询含变量的已知 key
- **THEN** 返回有效语言文案并替换参数，用户参数内容保持原样

#### Scenario: 缺失 key
- **WHEN** 当前语言文案缺失
- **THEN** 返回英文文案；英文也缺失时返回 key，不抛异常

### Requirement: SPEC-I18N-UI-003 所有用户可见 UI 文案
桌面 HTML 中的标题、标签、按钮、placeholder 和 tab，以及运行时生成的列表、提示、错误、确认框、
空状态和 toast SHALL 经消息目录渲染。console 日志、内部枚举、后端数据、用户文本、AW 数据和模型回复
不属于 UI 字面量本地化范围。

#### Scenario: 动态错误提示
- **WHEN** 前端需要渲染保存失败或空状态
- **THEN** 固定包裹文案来自 t()，后端或用户数据按安全转义原样插入

### Requirement: SPEC-I18N-DEC-007、SPEC-I18N-UI-004 语言生效时机
有效语言 SHALL 在后端启动时确定，`app.language` 保存后 SHALL 标记为需重启，当前后端、提示词、页面及就绪原生壳继续使用原语言。受管桌面 SHALL 在退出并重新启动整个应用后统一使用新语言；独立后端 SHALL 在重启后端并刷新页面后使用新语言。系统 MUST NOT 承诺整页热切换。

#### Scenario: 修改 app.language
- **WHEN** 用户把 app.language 从 zh 改为 en 并仅刷新 WebView
- **THEN** 当前 UI 和后端继续使用 zh，保存状态明确说明仍需重启

#### Scenario: 重启受管桌面
- **WHEN** 保存 en 后退出并重新启动桌面应用
- **THEN** 后端就绪后托盘、页面和新生成内容的提示词统一使用 en

### Requirement: SPEC-I18N-RES-001..003 单一解析与前端可见性
后端 SHALL 通过单一语言解析入口计算有效语言，提示词构建点 MUST 使用该结果而不是自行判断 Locale。桌面状态或初始化通道 SHALL 在业务首屏渲染前提供有效语言及其日期 Locale。后端语言尚不可得时，页面 SHALL 使用英文加载或错误提示，不把临时值宣称为后端有效语言；请求失败不得无限阻塞页面，并 SHALL 支持重试成功后按权威结果初始化。

#### Scenario: 前后端语言一致
- **WHEN** 后端解析 effective language=en
- **THEN** 状态通道提供 en，前端目录、日期格式与后端提示词采用对应语言配置

#### Scenario: 初始化失败后恢复
- **WHEN** 首次读取状态失败，随后重试成功并返回 zh
- **THEN** 页面先显示可重试的英文提示，再按 zh 初始化静态与动态内容，不遗留英文临时提示

### Requirement: SPEC-I18N-UI-005、SPEC-I18N-CFG-001、002 配置入口
`app.language` SHALL 默认 auto，并通过用户 TOML、结构化配置和 ConfigTools 读写。该键 SHALL 位于后端白名单和 restartRequired 集合。桌面设置 SHALL 提供 auto、中文、English 选择入口，显示已保存选择及当前有效语言；该入口 SHALL 使用既有配置保存流程，保留无关配置及未保存编辑，保存提示 SHALL 明确重启要求。

#### Scenario: Agent 写入语言配置
- **WHEN** ConfigTools 设置 app.language=en
- **THEN** 值写入 config.toml，并返回需重启的提示，当前有效语言保持不变

#### Scenario: 设置页面选择语言
- **WHEN** 用户在包含其他未保存修改的设置中选择 English 并保存
- **THEN** 语言与其他编辑一起按既有流程保存，界面显示 English 已保存及当前仍使用的语言，不覆盖无关配置

### Requirement: SPEC-I18N-DEC-008、SPEC-I18N-NON-001..007 本地化范围
正式产品当前 SHALL 只开放中英文，语言扩展机制 MUST 允许后续通过注册语言及提供对应资源接入新语言。当前能力 MUST NOT 承诺 ICU 复数、RTL、区域货币格式、运行时 LLM 翻译、前端打包框架或整页热切换。源码注释、日志、项目文档、config.toml 模板注释、ActivityWatch 自带 Web UI、OS 应用/窗口名和已落盘历史数据 MUST NOT 被运行时回填翻译；项目文档 SHALL 继续使用简体中文。

#### Scenario: 历史摘要语言
- **WHEN** 用户切换 effective language
- **THEN** 新摘要使用新语言，已有摘要保持生成时语言

### Requirement: SPEC-I18N-NATIVE-001 原生桌面语言
托盘入口、关于、自启动状态及应用自有原生提示 SHALL 提供中英文资源。后端就绪后原生壳 SHALL 使用受认证通道提供的后端有效语言。后端就绪前的启动失败及第二实例唤起失败提示 SHALL 根据系统 Locale 选择中文或英文，其他语言回退英文；该临时语言 MUST NOT 覆盖后端配置。语言读取失败 SHALL 使用有界重试或报告本地化启动错误，不得进入托盘和页面语言不一致的正常就绪状态；MUST NOT 绕过端口、认证及单实例限制。

#### Scenario: 英文托盘
- **WHEN** 后端有效语言为 en 且桌面完成启动
- **THEN** 托盘各入口、关于和自启动结果提示均使用英文，勾选、聚焦及退出行为保持不变

#### Scenario: 后端未能启动
- **WHEN** 系统为中文且后端在发布有效语言前启动失败
- **THEN** 壳使用中文失败提示；系统为未支持语言时使用英文，不依赖后端翻译

### Requirement: SPEC-I18N-ERROR-001 用户可见错误本地化
应用自有、面向用户的桌面 API 错误 SHALL 提供稳定错误码和必要参数，保留既有 HTTP 状态及原错误字段。客户端 SHALL 按错误码渲染当前语言固定文案，后端原错误字段中的应用自有固定文案 SHALL 使用后端有效语言。旧响应、未知码或第三方异常 SHALL 显示当前语言通用提示并安全呈现已有可公开诊断信息；MUST NOT 翻译用户路径或原文，MUST NOT 因国际化增加密钥或内部堆栈暴露。

#### Scenario: 英文目录校验错误
- **WHEN** 有效语言为 en，用户保存不存在的监控目录
- **THEN** 返回稳定错误码，页面及兼容错误字段使用英文固定提示，路径内容保持原样

#### Scenario: 未知错误兼容
- **WHEN** 页面收到只有旧错误字段的响应或未知错误码
- **THEN** 显示本地化通用失败提示并安全处理已有错误详情，不崩溃也不直接显示无法理解的 key

### Requirement: SPEC-I18N-EXT-001 资源校验与扩展验证
正式支持的每种语言 SHALL 覆盖页面、后端固定提示、原生消息及所需提示词资源；构建验证 SHALL 拒绝缺失 key、重复 key 或命名参数集合不一致。运行时面对损坏资源仍 SHALL 按约定回退。测试环境 SHALL 能注册测试语言，验证语言选择、消息参数、日期 Locale、原生消息及提示词资源选择，无须修改业务调用点；测试语言 MUST NOT 出现在正式产品选项中。

#### Scenario: 资源遗漏
- **WHEN** 正式语言资源缺少一条消息或与英文参数集合不一致
- **THEN** 校验失败并指出语言及消息 key

#### Scenario: 测试语言扩展
- **WHEN** 测试注册额外语言并提供对应资源和日期 Locale
- **THEN** 通用选择链路使用该语言及其资源，未提供的测试消息回退英文，正式产品仍仅提供 auto、中文及 English
