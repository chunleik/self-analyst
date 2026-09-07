## MODIFIED Requirements

### Requirement: SPEC-AGT-002 动态系统上下文
基础 system prompt SHALL 按有效语言包含 SelfAnalyst 身份、数据分析与历史回顾、当前日期及已启用工具
说明。当前日期 SHALL 在 Agent 构造时生成；可变长期记忆 MUST NOT 固化到基础 prompt，而 SHALL 在
每次 invocation 由动态记忆中间件读取最新 profile 后追加。滚动会话 summary 与本轮 desktop context
SHALL 仅临时注入模型输入，不污染 AgentState.context。
基础 prompt 描述本地事件数据、查询工具和时间戳时 MUST NOT 使用 ActivityWatch 品牌名，SHALL 使用
事件数据、事件查询工具等产品内用语。

#### Scenario: 记忆在两轮间变化
- **WHEN** 用户在两次聊天之间批准或修改 active 记忆
- **THEN** 第二轮 system context 包含最新记忆，基础 prompt 无需重建

#### Scenario: 临时桌面上下文
- **WHEN** 当前 turn 具有 contextSnapshot
- **THEN** 模型输入临时包含 reference-only context，持久 AgentState 不保存该临时消息

#### Scenario: 系统提示不含品牌名
- **WHEN** Agent 构造基础 system prompt
- **THEN** 中英文提示均不包含字符串 ActivityWatch
