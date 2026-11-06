## MODIFIED Requirements

### Requirement: SPEC-AGT-001 配置驱动的对话模型
Agent SHALL 根据有效 provider 使用 OpenAI-compatible API Key chat model 或 ChatGPT OAuth Responses model；API Key 模式读取 base URL、model、temperature，ChatGPT 模式读取独立账户及模型选择，maxIters 继续使用既有配置。所有生成调用 MUST NOT 发送客户端输出上限参数，包括 max_tokens 与 max_completion_tokens。对话模型 SHALL 支持流式输出；plain/summary 模型 SHALL 向调用方提供非流式完整结果；API Key 模式使用低温配置，ChatGPT 模式只发送其协议支持的生成参数。
每次模型执行 SHALL 使用 45 秒超时和单次尝试，除非未来显式修改契约。所选连接缺少有效 API key 或具备推理权限的 ChatGPT 授权时，
应用本地服务 SHALL 继续启动，聊天 SHALL unavailable/degraded；之后通过应用保存有效密钥或选择具备推理权限的已授权 ChatGPT 账户 SHALL
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

#### Scenario: ChatGPT 没有直接模型权限
- **WHEN** 所选 ChatGPT 账户只有有效登录身份但缺少直接模型访问授权
- **THEN** 本地服务继续运行，新模型工作明确要求补充授权或套餐权限，不自动使用已保存 API Key

### Requirement: SPEC-AGT-LIVE-002 首次配置恢复与移除配置
本地存储、桌面会话和基础采集 SHALL 能在 LLM 未配置时保持可用。首次补填有效连接配置后，新的聊天、
摘要以及其它依赖已满足的 Wiki LLM 工作 SHALL 无需重启恢复，状态接口与界面 SHALL 反映实际可用性。
系统 MUST NOT 因恢复 LLM 而打开用户禁用的 Wiki、Embedding、联网搜索或采集功能。
删除或清空配置后若所选 API Key 为空或占位值，或所选 ChatGPT 账户已断开或不具备推理权限，后续新 LLM 工作 SHALL 报告未配置，正在执行的旧工作
SHALL 按既有完成、取消及超时流程结束；重新补填后 SHALL 可再次恢复。

#### Scenario: 初次填写密钥
- **WHEN** 应用因没有 LLM 配置而启动为降级状态，用户随后保存有效连接配置
- **THEN** 后续聊天可开始，已满足其它依赖的摘要服务可使用模型，已有会话与长期记忆仍可访问

#### Scenario: 清空后再次填写
- **WHEN** 用户将有效密钥清空，之后再次保存有效密钥
- **THEN** 中间的新工作明确不可用，后续恢复且不丢失原会话和用量

#### Scenario: 更换已授权账户
- **WHEN** 用户保存另一个具备所需授权的 ChatGPT 账户和模型
- **THEN** 新工作使用新连接，既有工作继续持有原模型版本，不清空本地会话、不绕过预算、不自动启用 Embedding
