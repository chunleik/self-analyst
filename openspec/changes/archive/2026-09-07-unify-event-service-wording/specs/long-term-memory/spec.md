## REMOVED Requirements

### Requirement: SPEC-LTM-NON-001..006 功能边界
**Reason**: 场景名含 ActivityWatch 品牌名，指代的却是本产品自研采集事件；OpenSpec 无法单独重命名场景，
故整体替换为同 ID 的新条款。行为契约不变。
**Migration**: 见本 capability 中「SPEC-LTM-NON-001..006 功能边界与事件来源」，SPEC ID 与约束原样承接。

## ADDED Requirements

### Requirement: SPEC-LTM-NON-001..006 功能边界与事件来源
长期记忆 SHALL 保持本地文件能力，不提供云同步、账号体系、跨设备合并、向量召回或 embedding 检索。
系统 MUST NOT 自动把原始采集事件或全部聊天原文复制为长期记忆；legacy goals、patterns、
logs 不要求迁移成 MemoryItem，但 SHALL 继续兼容读取和上下文展示。

#### Scenario: 原始采集事件
- **WHEN** 系统存在新的窗口、AFK 或内容事件
- **THEN** 这些原始事件不会自动写入长期记忆；行为推断只能经有界候选和确认策略处理

## MODIFIED Requirements

### Requirement: SPEC-LTM-DEC-007、SPEC-LTM-EXTR-002 有界提炼输入
自动提炼输入 SHALL 限于当前会话 ID、标题、策略、最近 user/assistant turn 或有界消息窗口、相关消息 ID，
以及 active/pending 记忆的有界摘要。输入 MUST NOT 包含配置密钥、凭据、完整历史会话库或原始采集
事件流；凭据样式的聊天文本 SHALL 在调用提炼模型前被确定性拦截。

#### Scenario: 有界 prompt
- **WHEN** 当前消息和既有记忆内容很长
- **THEN** 提炼 prompt 只包含截断后的近期消息、记忆摘要、策略和来源 ID，并保持在固定预算内

#### Scenario: 凭据样式聊天文本
- **WHEN** 最近 user/assistant turn 包含 API key、client secret 或等价凭据
- **THEN** 系统不把该 turn 发送给提炼模型，也不创建候选

### Requirement: SPEC-LTM-EXTR-004、005 自动与待确认条件
只有用户明确陈述、非敏感、非凭据、可长期复用、confidence 至少 8 且未重复的候选 MAY 自动 active。
模型推断、行为模式、敏感类别、采集事件行为归纳、confidence 低于 8 或 confirm_all 会话候选
MUST pending。系统 MUST NOT 仅根据模型给出的 approvalPolicy 绕过确定性分级。

#### Scenario: 模型把敏感候选标为 auto
- **WHEN** 候选标记 sensitive 或属于敏感推断，但模型建议 auto
- **THEN** 系统不自动 active，候选进入 pending 或被拒绝
