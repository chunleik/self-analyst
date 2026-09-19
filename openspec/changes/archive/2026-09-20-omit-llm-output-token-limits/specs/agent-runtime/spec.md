## MODIFIED Requirements

### Requirement: SPEC-AGT-001 配置驱动的对话模型
Agent SHALL 使用 OpenAI-compatible chat model，并从有效配置读取 base URL、model、temperature 和 maxIters。所有生成调用 MUST NOT 发送客户端输出上限参数，包括 max_tokens 与 max_completion_tokens。对话模型 SHALL 支持流式输出；plain/summary 模型 SHALL 使用非流式低温配置。
每次模型执行 SHALL 使用 45 秒超时和单次尝试，除非未来显式修改契约。配置缺少有效 API key 时，
应用本地服务 SHALL 继续启动，聊天 SHALL unavailable/degraded；之后通过应用保存有效密钥 SHALL
允许新的聊天恢复可用。LLM 连接参数 SHALL 在新工作开始时生效，maxIters 仍在 Agent 重建或应用重启后生效。

#### Scenario: 自定义模型配置
- **WHEN** 用户通过应用保存不同 model、base URL、temperature
- **THEN** 后续新工作使用保存后的有效配置，无需重启；plain/summary 保持低温非流式策略

#### Scenario: API key 缺失
- **WHEN** LLM API key 为空或占位值
- **THEN** 本地采集和桌面服务继续启动，Agent 对话报告不可用

#### Scenario: maxIters 启动期配置
- **WHEN** 用户修改 maxIters 但未重启应用
- **THEN** 当前 Agent 的迭代上限保持不变并提示需重启；随后仅修改模型也不得意外应用该上限
