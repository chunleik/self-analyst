# 国际化规格

## Purpose

定义中文与英文的有效语言解析、后端 LLM 提示词、桌面 UI 消息目录、配置入口和生效时机，使界面与模型回复使用一致语言而不改变业务数据契约。

## Requirements

### Requirement: SPEC-I18N-DEC-001..004、SPEC-I18N-GOAL-001..006 有效语言与兼容边界
系统 SHALL 只支持 zh 和 en 两种有效语言。`app.language` SHALL 为 zh/en/auto，默认 auto；显式值直接
选择语言，auto 或非法值 SHALL 根据系统 Locale 解析：语言代码以 zh 开头为 zh，否则 en。有效语言
SHALL 由后端单一入口解析并通过状态通道提供前端，提示词和 UI MUST 使用同一结论。国际化 MUST NOT
改变现有布局、交互、数据 shape、发送或持久化语义。

#### Scenario: 中文系统自动解析
- **WHEN** app.language=auto 且系统 Locale 语言以 zh 开头
- **THEN** effective language 为 zh

#### Scenario: 非中文系统自动解析
- **WHEN** app.language=auto 或非法值且系统 Locale 为 en-US
- **THEN** effective language 为 en，解析不抛错

#### Scenario: 显式覆盖
- **WHEN** 系统 Locale 为英文但 app.language=zh
- **THEN** 后端与前端使用 zh

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
桌面 UI SHALL 使用稳定 key→zh/en 消息目录和 `t(key, params)` 查找函数，消息目录 SHALL 在业务模块前
加载且不引入打包器或框架。查找 SHALL 支持参数替换；key 或当前语言文案缺失时 MUST 确定性回退到
另一语言或 key 本身，不能中断渲染。

#### Scenario: 参数化文案
- **WHEN** t() 查询含变量的已知 key
- **THEN** 返回 effective language 文案并替换参数

#### Scenario: 缺失 key
- **WHEN** key 或当前语言列缺失
- **THEN** t() 返回确定性 fallback，不抛异常

### Requirement: SPEC-I18N-UI-003 所有用户可见 UI 文案
桌面 HTML 中的标题、标签、按钮、placeholder 和 tab，以及运行时生成的列表、提示、错误、确认框、
空状态和 toast SHALL 经消息目录渲染。console 日志、内部枚举、后端数据、用户文本、AW 数据和模型回复
不属于 UI 字面量本地化范围。

#### Scenario: 动态错误提示
- **WHEN** 前端需要渲染保存失败或空状态
- **THEN** 固定包裹文案来自 t()，后端或用户数据按安全转义原样插入

### Requirement: SPEC-I18N-DEC-007、SPEC-I18N-UI-004 语言生效时机
后端 effective language SHALL 来自启动期不可变 Config；app.language 修改后，Agent 和后端提示词语言
只有重启后端才生效。前端 SHALL 在加载时读取后端 effective language，刷新或重开 WebView 后更新 UI；
当前系统 MUST NOT 承诺运行时整页热切换。

#### Scenario: 修改 app.language
- **WHEN** 用户把 app.language 从 zh 改为 en
- **THEN** 刷新 WebView 可更新界面，重启后端后 Agent/摘要提示切换为英文

### Requirement: SPEC-I18N-RES-001..003 单一解析与前端可见性
后端 SHALL 通过单一语言解析 API 计算 zh/en，各提示词构建点 MUST 使用该结果而不是自行判断 Locale。
桌面状态或初始化通道 SHALL 在首屏文案渲染前向前端提供相同 effective language。

#### Scenario: 前后端语言一致
- **WHEN** 后端解析 effective language=en
- **THEN** 状态通道提供 en，前端消息目录与后端提示词均选择 English

### Requirement: SPEC-I18N-UI-005、SPEC-I18N-CFG-001、002 配置入口
`app.language` SHALL 默认 auto，并通过用户 TOML、结构化配置和 ConfigTools 读写。该键 SHALL 位于
后端白名单和 restartRequired 集合；保存提示 SHALL 说明后端需重启、界面需刷新。raw config.toml
编辑器提供最低语言选择入口，额外下拉控件不是必需。

#### Scenario: Agent 写入语言配置
- **WHEN** ConfigTools 设置 app.language=en
- **THEN** 值写入 config.toml，并返回需重启后端的提示

### Requirement: SPEC-I18N-DEC-008、SPEC-I18N-NON-001..007 本地化范围
当前能力 MUST NOT 承诺中英以外语言、ICU 复数、RTL、区域货币格式、运行时 LLM 翻译、前端打包框架
或整页热切换。源码注释、日志、项目文档、config.toml 模板注释、ActivityWatch 自带 Web UI、OS 应用/
窗口名和已落盘历史数据 MUST NOT 被运行时回填翻译；项目文档 SHALL 继续使用简体中文。

#### Scenario: 历史摘要语言
- **WHEN** 用户切换 effective language
- **THEN** 新摘要使用新语言，已有摘要保持生成时语言
