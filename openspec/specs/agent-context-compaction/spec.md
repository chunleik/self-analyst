# AgentState 长会话压缩规格

## Purpose

定义长会话中 UI transcript、AgentState 近期上下文与滚动摘要的分层权威关系，以及压缩触发、事务提交、完整 user turn、临时桌面上下文和 token 计量行为。

## Requirements

### Requirement: SPEC-ACOMP-001 双层会话历史
`chat.db` SHALL 保存用户可见的完整 UI transcript；AgentState SHALL 只保存模型近期原始消息和更早历史
的滚动 summary。模型推理时 SHALL 临时组合 summary 与 recent context；summary 注入消息 MUST NOT
写回 AgentState.context。当前桌面 contextSnapshot SHALL 通过 RuntimeContext 临时注入，不拼接进
持久 user message。

#### Scenario: 推理使用摘要和近期上下文
- **WHEN** AgentState 同时具有 rolling summary 和近期消息
- **THEN** 模型输入包含临时 summary 消息与近期上下文，而持久 AgentState.context 保持原近期消息列表

#### Scenario: UI transcript 不被压缩删除
- **WHEN** AgentState 完成上下文压缩
- **THEN** `chat.db` 中用户可见会话正文保持不变

### Requirement: SPEC-ACOMP-010 压缩配置与触发
压缩 SHALL 默认启用，默认 message/token 触发阈值分别为 30 和 60000，默认保留窗口分别为 10 条消息
和 12000 tokens。message 或 token 任一启用阈值达到时 SHALL 尝试压缩；某阈值为 0 时 SHALL 禁用该
触发条件。`keepTokens>0` 时 SHALL 使用 token tail，否则使用有界 message tail；只启用 token 阈值但
keepTokens 为 0 时 SHALL 归一化为低于触发阈值的安全保留窗口。keepMessages MUST 不超过 100，
keepTokens MUST 不超过 64000。配置变更 SHALL 在 Agent 重建或应用重启后生效。

#### Scenario: message 阈值触发
- **WHEN** message 触发阈值大于 0 且 AgentState.context 消息数达到阈值
- **THEN** 系统尝试压缩，即使 token 阈值尚未达到

#### Scenario: 单项阈值关闭
- **WHEN** message 或 token 触发阈值配置为 0
- **THEN** 该单项条件不触发压缩，另一启用条件仍可触发

### Requirement: SPEC-ACOMP-020 压缩前置条件与会话协调
压缩 SHALL 在现有聊天或删除会话 gate 内执行。旧 transcript 存在但 AgentState 缺失时，系统 SHALL
先完成一次性 seed；已有同 userMessageId terminal assistant 时 SHALL 直接返回已有回复，不调用摘要
模型。AgentState 标记 shutdown interrupted、没有上下文或存在未配对 ToolUse/ToolResult 时 SHALL
跳过压缩。

#### Scenario: 已完成 turn 幂等重放
- **WHEN** 相同 userMessageId 已有 terminal assistant
- **THEN** 系统返回已有结果，不触发上下文压缩或摘要调用

#### Scenario: 未配对工具调用
- **WHEN** AgentState.context 中 ToolUse 与 ToolResult ID 计数不匹配
- **THEN** 系统跳过压缩，保留原上下文

#### Scenario: shutdown 中断状态
- **WHEN** AgentState 标记 shutdownInterrupted
- **THEN** 系统不压缩该状态，等待恢复流程处理

### Requirement: SPEC-ACOMP-021 事务性摘要提交与完整 user turn
压缩结果只有在摘要非空、不是失败 marker、recent tail 非空，且压缩期间原 summary/context 未变化时
才 SHALL 提交。若裁剪点落在 assistant 消息上，系统 SHALL 向前扩展到所属 user 消息，避免拆散完整
turn。提交 SHALL 在同一 AgentState 中更新 summary、替换 context 并原子保存；permission、tool、task、
plan mode、reply ID 等其它状态 MUST 保持不变。摘要失败、保存失败或状态并发变化时 SHALL 保留原磁盘
状态，并在退出前清除缓存。

#### Scenario: 成功压缩
- **WHEN** 摘要有效、recent tail 可用且原 AgentState 在压缩期间未变化
- **THEN** 系统保存新 rolling summary 和完整 user-turn recent tail，其它 AgentState 字段保持不变

#### Scenario: 摘要不可用
- **WHEN** 摘要为空、包含失败 marker 或摘要模型报错
- **THEN** 系统不替换历史，磁盘 AgentState 保持压缩前内容

#### Scenario: 原子保存失败
- **WHEN** 保存压缩后 AgentState 失败
- **THEN** 系统恢复或保留原磁盘状态，不留下部分 summary/context 更新

### Requirement: SPEC-ACOMP-030 临时桌面上下文
会话模型请求 SHALL 使用数据库中 server-owned user content 和 contextSnapshot，忽略请求体伪造的正文。
contextSnapshot SHALL 通过 RuntimeContext 传递，并在每次 reasoning 前作为 reference-only USER 消息
插入当前 user 之前。序列化投影 MUST 不超过 12000 字符，并 SHALL 限制 task、timeline 和 evidence
字段及长度。临时上下文消息 MUST NOT 写入 AgentState.context。

#### Scenario: 注入本轮上下文
- **WHEN** 当前 user turn 带有合法 contextSnapshot
- **THEN** 模型输入在当前 user 前包含有界 reference-only 上下文，持久 AgentState 不包含该临时消息

#### Scenario: 请求体伪造 user 内容
- **WHEN** 聊天请求体中的 user 文本与数据库 canonical 消息不同
- **THEN** 系统使用数据库内容和 contextSnapshot 路由模型

### Requirement: SPEC-ACOMP-040 工具结果上下文预算
事件查询工具 `queryEvents` 的 limit MUST 不超过 500；任一事件查询工具结果 MUST 限制为最多
80000 字符，以防单次工具结果撑满当前 reasoning 上下文。超限结果 SHALL 截断或拒绝，而不得无界注入。

#### Scenario: 过大事件查询
- **WHEN** Agent 请求超过 500 条投影事件
- **THEN** 工具将实际查询限制在 500 条以内

#### Scenario: 过大工具结果
- **WHEN** 事件查询工具序列化结果超过 80000 字符
- **THEN** 返回给 Agent 的结果被限制在预算内

### Requirement: SPEC-ACOMP-050 压缩用量与预算
压缩摘要模型调用 SHALL 计入 SUMMARY 类别的 token 用量，并继续受每日预算模式控制。压缩调用失败
或被预算阻止时 MUST NOT 破坏原 AgentState。

#### Scenario: 压缩 token 计量
- **WHEN** 压缩摘要模型完成调用并返回 usage
- **THEN** 输入和输出 token 计入当日 SUMMARY 类别

#### Scenario: 预算阻止摘要
- **WHEN** 当前预算模式阻止 summary 调用
- **THEN** 本轮压缩跳过或失败，原 summary/context 保持不变
