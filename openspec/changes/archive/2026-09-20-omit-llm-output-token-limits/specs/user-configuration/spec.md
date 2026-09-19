## MODIFIED Requirements

### Requirement: SPEC-CFG-LIVE-001 LLM 应用范围与组件差异
应用内保存 SHALL 支持 llm.api-key、llm.base-url、llm.model、llm.temperature
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
- **WHEN** raw、结构化或 Agent 工具提交类型错误、非有限或超出 0..2 的温度或不可用作 HTTP(S) 基础地址的值
- **THEN** 系统按相同规则拒绝，保留原文件和当前运行配置；缺失或空密钥按未配置状态处理

#### Scenario: 结构化新采集配置
- **WHEN** 当前事件服务以默认标题配置运行，客户端提交 `{"collection":{"title":{"enabled":false,"pollMs":800}}}`
- **THEN** 保存 `events.collection.title.enabled=false` 和 `events.collection.title.pollMs=800`，输出 TOML 可解析，响应准确报告事件服务组件需重启

#### Scenario: Agent 使用已移除键
- **WHEN** Agent 配置工具尝试设置 `aw.collection.content`
- **THEN** 工具返回该名称已移除及应使用 `events.collection.title.enabled` 的错误，不写入配置、不报告成功

## ADDED Requirements

### Requirement: SPEC-CFG-OUTPUT-001 输出上限退役兼容

系统 SHALL 停用 llm.max-tokens 及 LLM_MAX_TOKENS，配置模板、有效配置、热更新策略、模型设置与 Agent 配置工具 MUST NOT 将其作为可用选项。合法 TOML 原文中的旧键 SHALL 保留但不参与解析或模型版本比较，其值不因数字范围或旧类型规则阻止正常配置保存。模型设置 API MUST 拒绝 maxTokens 的显式更新或恢复继承请求。其它预算、用量计量、迭代次数、输入压缩、超时及响应大小限制 SHALL 保持原有语义。

#### Scenario: 旧配置升级
- **WHEN** 旧 TOML 含正值、零、负值或字符串形式的 llm.max-tokens，或环境设置 LLM_MAX_TOKENS
- **THEN** 新程序忽略旧值，不发送输出上限，不因该退役值拒绝合法配置，raw 原文仍保留旧行

#### Scenario: 仅改变退役键
- **WHEN** 用户通过 raw 保存仅改变或删除 llm.max-tokens
- **THEN** 不重建 LLM 资源、不报告该键需重启，正常调用依旧不发送输出上限
