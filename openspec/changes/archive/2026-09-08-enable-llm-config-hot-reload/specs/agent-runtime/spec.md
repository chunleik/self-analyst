## MODIFIED Requirements

### Requirement: SPEC-AGT-001 配置驱动的对话模型
Agent SHALL 使用 OpenAI-compatible chat model，并从有效配置读取 base URL、model、temperature、
maxTokens 和 maxIters。对话模型 SHALL 支持流式输出；plain/summary 模型 SHALL 使用非流式低温配置。
每次模型执行 SHALL 使用 45 秒超时和单次尝试，除非未来显式修改契约。配置缺少有效 API key 时，
应用本地服务 SHALL 继续启动，聊天 SHALL unavailable/degraded；之后通过应用保存有效密钥 SHALL
允许新的聊天恢复可用。LLM 连接参数及输出上限 SHALL 在新工作开始时生效，maxIters 仍在 Agent 重建或应用重启后生效。

#### Scenario: 自定义模型配置
- **WHEN** 用户通过应用保存不同 model、base URL、temperature 或 maxTokens
- **THEN** 后续新工作使用保存后的有效配置，无需重启；plain/summary 保持低温非流式策略

#### Scenario: API key 缺失
- **WHEN** LLM API key 为空或占位值
- **THEN** 本地采集和桌面服务继续启动，Agent 对话报告不可用

#### Scenario: maxIters 启动期配置
- **WHEN** 用户修改 maxIters 但未重启应用
- **THEN** 当前 Agent 的迭代上限保持不变并提示需重启；随后仅修改模型也不得意外应用该上限

## ADDED Requirements

### Requirement: SPEC-AGT-LIVE-001 一轮工作固定模型版本
一轮聊天 SHALL 在获得既有应用级聊天 gate 后、开始压缩或推理前固定模型版本。该轮的压缩、推理、
工具调用和后续模型调用 SHALL 使用同一版本；其它独立摘要、Wiki 或长期记忆 LLM 工作 SHALL 在各自
工作开始时固定版本。配置发布 MUST NOT 中断现有流、清空上下文或解除应用级聊天互斥。
保存成功后开始的新工作 SHALL 采用新版本，包括先创建惰性请求、后实际开始的工作。

#### Scenario: 工具调用期间切换模型
- **WHEN** 当前轮完成首次推理、等待工具执行时用户保存新模型
- **THEN** 该轮工具之后的推理仍使用旧模型，下一轮使用新模型，历史和工具结果保持完整

#### Scenario: 新版本摘要与旧回答并行
- **WHEN** 保存后旧回答仍在输出且新的独立摘要任务开始
- **THEN** 旧回答使用旧版本，摘要使用新版本，各自的模型名、参数和计量对应实际调用

#### Scenario: 惰性请求在保存后开始
- **WHEN** 请求对象在保存前创建，但实际执行在保存成功后开始
- **THEN** 请求采用新版本，不因提前创建对象而固定旧配置

### Requirement: SPEC-AGT-LIVE-002 首次配置恢复与移除配置
本地存储、桌面会话和基础采集 SHALL 能在 LLM 未配置时保持可用。首次补填有效连接配置后，新的聊天、
摘要以及其它依赖已满足的 Wiki LLM 工作 SHALL 无需重启恢复，状态接口与界面 SHALL 反映实际可用性。
系统 MUST NOT 因恢复 LLM 而打开用户禁用的 Wiki、Embedding、联网搜索或采集功能。
删除或清空配置后若解析所得密钥为空或占位值，后续新 LLM 工作 SHALL 报告未配置，正在执行的旧工作
SHALL 按既有完成、取消及超时流程结束；重新补填后 SHALL 可再次恢复。

#### Scenario: 初次填写密钥
- **WHEN** 应用因没有 LLM 配置而启动为降级状态，用户随后保存有效连接配置
- **THEN** 后续聊天可开始，已满足其它依赖的摘要服务可使用模型，已有会话与长期记忆仍可访问

#### Scenario: 清空后再次填写
- **WHEN** 用户将有效密钥清空，之后再次保存有效密钥
- **THEN** 中间的新工作明确不可用，后续恢复且不丢失原会话和用量

### Requirement: SPEC-AGT-LIVE-003 运行资源释放
模型切换 SHALL 保留会话、工具与计量的共享资源，旧模型资源 SHALL 在没有工作使用它后释放。
完成、取消、异常和关闭路径 MUST 释放对应工作持有的模型资源引用。重复切换 MUST NOT 重复注册工具、
重开同一会话数据库或永久保留旧客户端。应用关闭 SHALL 先禁止新工作，再按既有取消／结束规则收敛工作，
并幂等释放新旧运行资源及共享资源。

#### Scenario: 切换后取消旧回答
- **WHEN** 新模型已发布，旧回答被用户取消
- **THEN** 仅该回答被取消，旧模型不再被其它工作使用后可释放，新模型后续工作仍可用

#### Scenario: 多次切换并关闭
- **WHEN** 用户连续切换模型后关闭应用
- **THEN** 已无使用者的旧资源被回收，其余工作按关闭规则结束，工具及存储资源只关闭一次
