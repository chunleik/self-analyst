## MODIFIED Requirements

### Requirement: SPEC-ACOMP-010 压缩配置与触发
压缩 SHALL 默认启用，默认 message/token 触发阈值分别为 30 和 60000，默认保留窗口分别为 10 条消息
和 12000 tokens。message 或 token 任一启用阈值达到时 SHALL 尝试压缩；某阈值为 0 时 SHALL 禁用该
触发条件。`keepTokens>0` 时 SHALL 使用 token tail，否则使用有界 message tail；只启用 token 阈值但
keepTokens 为 0 时 SHALL 归一化为低于触发阈值的安全保留窗口。keepMessages MUST 不超过 100，
keepTokens MUST 不超过 64000。上述压缩开关与阈值变更 SHALL 在 Agent 重建或应用重启后生效；
LLM 连接参数与输出上限 SHALL 使用所属聊天轮次固定的模型版本，保持既有低温摘要策略。
仅切换模型 MUST NOT 提前应用尚需重启的压缩配置。

#### Scenario: message 阈值触发
- **WHEN** message 触发阈值大于 0 且 AgentState.context 消息数达到阈值
- **THEN** 系统尝试压缩，即使 token 阈值尚未达到

#### Scenario: 单项阈值关闭
- **WHEN** message 或 token 触发阈值配置为 0
- **THEN** 该单项条件不触发压缩，另一启用条件仍可触发

#### Scenario: 压缩中保存新模型
- **WHEN** 当前聊天已开始压缩且用户保存新的 LLM 连接配置或输出上限
- **THEN** 本次压缩及本轮后续推理使用旧版本；下一轮使用新版本，压缩的事务与计量约束保持不变

#### Scenario: 同时修改模型与压缩阈值
- **WHEN** 用户同时保存新的模型及压缩阈值而未重启应用
- **THEN** 下一轮使用新模型和当前运行的压缩阈值，并提示阈值变更需重启
