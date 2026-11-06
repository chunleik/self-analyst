## MODIFIED Requirements

### Requirement: SPEC-CFG-LIVE-001 LLM 应用范围与组件差异
应用内保存 SHALL 支持 llm.api-key、llm.base-url、llm.model、llm.temperature、llm.provider、llm.chatgpt-account、llm.chatgpt-model
对新工作生效。温度变更 SHALL 影响支持该参数的 API Key 聊天模型，plain/summary 与压缩 SHALL 保持既有低温策略；ChatGPT 仅发送其支持的参数，不修改另一认证方式的温度。
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

#### Scenario: provider 缺省与认证方式切换
- **WHEN** 旧配置缺少 provider，或用户显式切换至 ChatGPT
- **THEN** 旧配置继续使用 API Key；显式切换后新工作使用独立 ChatGPT 账户与模型，原有 API Key 连接字段保留


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
- **WHEN** 保存后所选 API Key 连接的有效密钥为空或占位值，或所选 ChatGPT 账户未连接或缺少直接模型访问权限
- **THEN** 界面更新已保存基准并显示 LLM 不可用及配置指引，不显示连接成功

#### Scenario: 从脏原文切换模型设置
- **WHEN** 原文有未保存修改且用户确认放弃后切换模型设置
- **THEN** 模型表单从已保存配置重新读取，不使用被放弃原文构建表单


### Requirement: SPEC-TOML-API-002 结构化端点与 ConfigTools
结构化配置端点和 ConfigTools SHALL 继续使用点分键、后端白名单、统一生效策略、应用结果及同一 config.toml。
LLM 与 Embedding 连接测试端点 SHALL 保持可用。结构化写入 MAY 重排并重生成 TOML，但 MUST 产生与
输入 flat map 等价的可解析结果。
结构化配置 SHALL 使用 `events` 事件服务分组及 `collection.title.enabled/pollMs` 标题采集字段；LLM 结构化配置增加 provider、chatgpt-account 和 chatgpt-model 三个非敏感选择字段，其余配置分组、字段与端点路径保持原有契约。旧 `aw` 分组中的本次移除配置以及 `collection.content`、`collection.content.pollMs` MUST 按 SPEC-TOML-RENAME-002 拒绝，不能静默跳过后报告成功。Agent 配置工具 SHALL 使用新点分键，并保持原有可写项范围，仅将对应旧可写键替换为新键。

#### Scenario: Agent 修改配置
- **WHEN** ConfigTools 设置允许键
- **THEN** 值经统一校验写入 config.toml，并返回新工作生效、需重启或不可用等准确结果

#### Scenario: Agent 在当前轮修改模型
- **WHEN** Agent 配置工具在正在执行的聊天轮次中保存新模型
- **THEN** 保存及时返回，当前轮继续完成，下一轮采用新模型，不等待当前轮结束而产生自锁

#### Scenario: 三种保存入口拒绝非法模型参数
- **WHEN** raw、结构化或 Agent 工具提交类型错误、非有限或超出 0..2 的温度或不可用作 HTTP(S) 基础地址的值
- **THEN** 系统按相同规则拒绝，保留原文件和当前运行配置；API Key 模式的缺失或空密钥按未配置状态处理，ChatGPT 模式按所选账户、模型和授权状态判断可用性

#### Scenario: 结构化新采集配置
- **WHEN** 当前事件服务以默认标题配置运行，客户端提交 `{"collection":{"title":{"enabled":false,"pollMs":800}}}`
- **THEN** 保存 `events.collection.title.enabled=false` 和 `events.collection.title.pollMs=800`，输出 TOML 可解析，响应准确报告事件服务组件需重启

#### Scenario: Agent 使用已移除键
- **WHEN** Agent 配置工具尝试设置 `aw.collection.content`
- **THEN** 工具返回该名称已移除及应使用 `events.collection.title.enabled` 的错误，不写入配置、不报告成功

## ADDED Requirements

### Requirement: SPEC-CFG-CGAUTH-001 OAuth 凭据与 Embedding 隔离
配置 SHALL 仅保存 llm.provider、llm.chatgpt-account、llm.chatgpt-model 等非敏感选择，OAuth token 和客户端秘密 MUST NOT 写入 TOML、配置导出、Agent 配置工具或普通有效配置响应。provider 缺省 SHALL 为 api-key；不支持的 provider 或错误字段类型 MUST 被明确拒绝。Embedding SHALL 继续使用独立的有效 API Key 配置，MUST NOT 继承 ChatGPT OAuth token 或把 OAuth 请求发往 Embedding 服务；切换 ChatGPT 不得自动启用 Embedding。

#### Scenario: 导出或读取配置
- **WHEN** 已登录 ChatGPT 后用户读取 TOML 或通过 Agent 查询配置
- **THEN** 只可见非敏感认证方式、账户与模型选择，响应不包含 OAuth token 或客户端秘密

#### Scenario: 使用 ChatGPT 并启用 Embedding
- **WHEN** 当前对话连接选择 ChatGPT 且用户原先启用了 Embedding
- **THEN** Embedding 继续依照自身 API Key 解析和运行策略执行，绝不使用 ChatGPT OAuth 凭据
