# Agent Runtime 规格

## Purpose

定义 SelfAnalyst Agent 的模型配置、工具注册、会话路由、动态记忆与桌面上下文、中间件预算计量，以及应用启动和优雅关闭行为。

## Requirements

### Requirement: SPEC-ARCH-001、SPEC-ARCH-004 依赖与事件查询边界
应用服务依赖 SHOULD 通过构造器注入；进程级原生资源 MAY 使用具有关闭与失败降级路径的受控共享实例。
Agent 的事件投影查询 SHALL 通过注册的 EventQueryTools；窗口、AFK、标题与文件 heartbeat 等
采集链 MAY 直接调用本地兼容 HTTP API。

#### Scenario: Agent 查询事件投影
- **WHEN** Agent 需要列出 bucket、查询事件或执行 AQL
- **THEN** 调用通过 EventQueryTools 发往配置的兼容 API base URL，不直接访问数据库

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

### Requirement: SPEC-AGT-003 工具注册与顺序执行
Agent SHALL 注册 EventQueryTools，并在相应依赖可用时注册 ConfigTools、WikiTools、FileTools 和
联网搜索 MCP。工具执行 SHALL 使用串行 toolkit，避免共享本地 store/connection 的并发访问。
可选工具初始化失败 MUST NOT 阻止其余 Agent 能力启动；失败客户端 SHALL 被关闭或跳过。

#### Scenario: 可选 Wiki/File/Config 工具不可用
- **WHEN** 某个可选 store 或配置入口未创建
- **THEN** Agent 不注册对应工具，其它已满足依赖的工具仍可使用

#### Scenario: 联网搜索初始化失败
- **WHEN** websearch 已启用但 MCP 连接或注册失败
- **THEN** Agent 跳过联网搜索并继续使用本地工具

### Requirement: SPEC-AGT-003 会话聊天与 stream
桌面会话聊天 SHALL 校验 hex32 sessionId 与可选 hex12 userMessageId，按 `(desktop, sessionId)` 路由
AgentState，并使用应用级 gate 保证同一时刻只有一个聊天执行。stream SHALL 产生 DELTA 事件和最终
canonical RESULT；非流式 chat SHALL 返回最后一个 RESULT。已有同 turn terminal assistant 时 SHALL
直接返回并跳过模型调用。取消 SHALL 只影响当前匹配 session/user turn。

#### Scenario: 同 turn 已完成
- **WHEN** AgentState 中已存在当前 userMessageId 的 terminal assistant
- **THEN** chat/stream 直接返回已有文本，不再次调用模型或工具

#### Scenario: 并发聊天
- **WHEN** 一个 Agent 聊天仍在执行时另一个会话尝试开始聊天
- **THEN** 第二个请求因 busy 失败，不并发执行共享工具

### Requirement: SPEC-AGT-004 记忆与 AgentState 持久化
Agent 构造 SHALL 从配置 memoryDir 加载 MemoryStore，并在关闭或显式保存时持久化 profile。
桌面 AgentState SHALL 存储在按 user/session 隔离的本地 state root。旧 transcript 需要 seed 且目标
AgentState 不存在时 MAY 一次性导入；已有 state MUST NOT 被重复覆盖。删除会话 SHALL 在同一 gate 内
删除对应 AgentState，并支持 Agent 初始化失败时的静态清理路径。

#### Scenario: 一次性 seed
- **WHEN** 旧会话 transcript 存在且对应 AgentState slot 不存在
- **THEN** 系统导入有效历史一次；后续请求复用已持久化 state

#### Scenario: 删除会话状态
- **WHEN** 会话删除流程取得 gate 并持久化删除 intent
- **THEN** 对应 AgentState 与 transcript 协调删除，不影响其它 session

### Requirement: SPEC-HOK-001、001a、001b、001c Agent 中间件透传与日志
reasoning 和 acting 中间件 SHALL 调用 next 并原样透传既有事件；预算阻断时 MAY 在 reasoning 结束后
追加 RequestStopEvent，但 MUST NOT 改写原事件。生命周期日志 SHALL 使用 SLF4J；中间件 SHOULD 只记录
受限运行状态，不使用 stdout/stderr 输出模型或用户正文。

#### Scenario: 正常 reasoning 事件
- **WHEN** 下游产生文本、思考或工具事件且预算未阻断
- **THEN** 中间件按原顺序透传事件，不修改内容

#### Scenario: block 预算达到
- **WHEN** reasoning 完成后每日 token 预算处于 block exceeded
- **THEN** 原事件继续透传，末尾追加 RequestStopEvent 终止后续 Agent 循环

### Requirement: SPEC-HOK-001d 模型 token 计量
Agent 中间件 SHALL 优先使用 ModelCallEndEvent 的 ChatUsage 记录 AGENT input/output tokens；缺少 usage 时
SHALL 对模型消息、工具 schema 和生成事件执行确定性估算。直接绕过 ReAct 中间件的 summary/compaction
模型 SHALL 通过独立计量包装记录相应类别。正常完成、错误、取消和同步抛错路径 MUST 至多记录一次。

#### Scenario: 服务返回 usage
- **WHEN** 模型结束事件提供 input/output token
- **THEN** UsageMeter 使用服务端数值，不再重复估算

#### Scenario: 模型取消且无 usage
- **WHEN** 模型流取消且没有 ChatUsage
- **THEN** 系统按输入和已生成内容估算并只记录一次

### Requirement: SPEC-APP-001 应用入口生命周期
应用入口 SHALL 创建 AppSession，注册 desktop 生命周期路由和可选端口发布，然后阻塞等待 shutdown
signal。JVM shutdown hook SHALL 调用 session.close 并释放等待。初始化失败 SHALL 记录错误、关闭已创建
session 并以非零状态退出。端口握手写入 SHALL 在目标同目录使用临时文件并优先原子替换。

#### Scenario: 正常桌面启动
- **WHEN** AppSession 初始化成功且桌面 token/port file 已配置
- **THEN** 应用注册认证 health/shutdown/session 路由，发布实际端口并等待关闭信号

#### Scenario: 启动失败
- **WHEN** AppSession 或端口发布抛出异常
- **THEN** 应用关闭已创建资源、记录失败并以状态 1 退出

### Requirement: SPEC-APP-002 AppSession 服务顺序与关闭
AppSession SHALL 先加载配置；embedded AW 模式下先执行内容迁移、启动 AW 和基础 watcher，再按依赖
创建 Agent、Wiki、文件采集和 DesktopServer。用户配置 store SHALL 在 Agent 前创建，使 ConfigTools
可用。关闭 SHALL 幂等保存 memory 和 usage，停止文件 worker、Wiki worker/index/store、DesktopServer、
内容/window/AFK watcher 与 AW，最后关闭 Agent 及其 MCP/state store。

#### Scenario: 内容迁移失败
- **WHEN** embedded AW 内容事件迁移失败
- **THEN** AW 与 window/AFK 可继续启动，标题 watcher 和新 Wiki facts worker 不启动，桌面状态报告降级

#### Scenario: 重复关闭
- **WHEN** shutdown hook 与桌面 shutdown signal 都调用 close
- **THEN** 资源只关闭一次，不重复写入或抛出生命周期错误

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
