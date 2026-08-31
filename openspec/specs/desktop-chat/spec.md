# 桌面会话页规格

## Purpose

定义桌面会话页的导航、会话缓存、消息发送与流式响应、取消和重试、业务上下文、建议任务、长期记忆入口及可访问性行为；持久化和 REST 细节由 chat-sessions 能力约束。

## Requirements

### Requirement: SPEC-CHAT-TAB-001 导航入口
桌面顶部导航 SHALL 同时保留 Agent、会话和配置入口；会话页 MUST NOT 取代 Agent 首页。从 Agent
摘要、时间线或建议入口进入会话页时，系统 MAY 携带本轮业务上下文和预填问题。

#### Scenario: 从主导航进入会话
- **WHEN** 用户选择“会话”入口
- **THEN** 桌面切换到会话页，并保留其它顶级入口

#### Scenario: 从 Agent 上下文进入
- **WHEN** 用户从摘要、时间线或建议操作选择继续追问
- **THEN** 会话页收到相应业务上下文和可编辑草稿

### Requirement: SPEC-CHAT-TAB-002 会话页布局与降级消息面
会话页 SHALL 提供会话列表、主消息区和可选上下文或记忆区域。列表 SHALL 支持新建、选择、重命名、
删除、搜索和继续加载；主消息区 SHALL 优先使用增强消息组件，并在组件未升级、超时或内部消息面不可用
时切换到原生 fallback。窄窗口 MUST 保持主要操作可用，独立区域 SHALL 可滚动且不得造成不可操作的
横向溢出。

#### Scenario: 增强消息组件可用
- **WHEN** 消息组件正常升级并完成渲染
- **THEN** 会话页使用该消息面显示 canonical 消息和 composer

#### Scenario: 消息组件不可用
- **WHEN** 消息组件未在等待期内升级或内部消息面不可用
- **THEN** 页面在 2 秒内启用原生 fallback，持久化和 API 语义保持不变

#### Scenario: 窄窗口
- **WHEN** 页面宽度接近最小桌面窗口
- **THEN** 面板折叠或纵向重排，列表、composer 和主要按钮仍可操作

### Requirement: SPEC-CHAT-TAB-003 创建与激活
新会话 SHALL 通过后端创建，并且会话 ID、时间戳和初始字段 MUST 使用服务端返回值；前端 MUST NOT
预生成会话或消息 ID。激活会话 SHALL 持久化后端 active 指针，刷新或重启后 SHALL 从后端恢复。
从上下文入口发送首条消息且没有现有会话时，前端 SHALL 先创建会话再发送。

#### Scenario: 创建新会话
- **WHEN** 用户创建新会话
- **THEN** 前端调用后端创建接口，并使用返回的 server-owned 会话数据激活该会话

#### Scenario: 恢复 active 会话
- **WHEN** 页面初始化且后端保存了 active session
- **THEN** 前端恢复该会话；若其不在首屏列表，则单独读取而不改写后端指针

#### Scenario: 上下文入口首次发送
- **WHEN** 用户从业务上下文入口确认发送且当前没有会话
- **THEN** 前端先创建并激活会话，再执行消息追加与模型请求

### Requirement: SPEC-CHAT-TAB-004 列表、搜索与正文懒加载
初始列表 SHALL 请求最多 50 条会话元数据，并按后端顺序显示；继续加载 SHALL 由稳定 cursor 和
`hasMore` 驱动。搜索 SHALL 把查询交给后端，不在前端扫描全部正文。列表缓存 MUST 只把元数据视为
已加载；选中会话后 SHALL 懒加载完整消息。并发列表、搜索、分页、正文读取和 mutation 响应 MUST
通过 request generation、mutation generation 与当前会话 ID 丢弃陈旧结果。

#### Scenario: 初始元数据列表
- **WHEN** 会话页首次加载
- **THEN** 前端请求首批元数据，不为每个列表项预先加载完整消息

#### Scenario: 选择未加载会话
- **WHEN** 用户选择只有元数据缓存的会话
- **THEN** 页面立即显示加载状态，并单独请求该会话的完整正文

#### Scenario: 搜索与分页响应乱序
- **WHEN** 旧查询或旧 cursor 响应晚于新查询、mutation 或会话选择返回
- **THEN** 前端丢弃陈旧响应，不覆盖当前列表或活动会话

#### Scenario: 删除冲突或失败
- **WHEN** 用户确认删除但后端返回 409 或其它失败
- **THEN** 前端保留当前缓存和会话，不伪造成功删除

### Requirement: SPEC-CHAT-TAB-005 新消息发送、流与取消顺序
新消息发送 SHALL 先确认 active 会话和正文可用，再由后端原子追加 server-owned user 与 pending
assistant，然后使用返回的 session/user ID 和业务 context 发起模型流。delta MUST 只显示在发起请求的
会话；完成后 SHALL 用后端 canonical message 更新原 pending，不追加第二条 assistant。若 terminal
文本为空但已有非空 delta，后端 SHALL 先把累计 delta 安全写回同一 turn，再作为 canonical 结果；
无法安全写回或 result/delta 都为空时发送 MUST 失败。同一前端时刻只允许一个发送流程。

#### Scenario: 正常流式发送
- **WHEN** user 与 pending 已持久化且模型持续产生文本 delta
- **THEN** delta 只显示在所属会话，完成后原 pending 被 canonical assistant 替换

#### Scenario: 发送期间切换会话
- **WHEN** 用户在流式响应期间切换到其他会话
- **THEN** 请求仍属于原 session，delta 不写入新活动会话

#### Scenario: 只有 delta 没有 terminal 文本
- **WHEN** 模型流产生非空 delta，但 terminal result 为空
- **THEN** 后端将累计 delta 原子写回同一 AgentState turn 后返回 canonical 结果；写回失败则返回错误

#### Scenario: 用户停止生成
- **WHEN** 用户点击停止按钮
- **THEN** 前端请求取消当前会话执行，并终止本地流读取；取消请求失败或超时不阻止本地终止

### Requirement: SPEC-CHAT-TAB-006 有界业务上下文
聊天请求 context MAY 包含来源、时间范围、时间线条目、建议任务和用户选择的记忆信息。后端 SHALL
限制 JSON 深度、节点数、容器宽度和字节数，并在超限时保留可识别字段的有界投影。context MUST NOT
包含 API key、完整配置或 UI transcript；本轮 context SHALL 通过运行时上下文临时注入，不写入
持久 user 消息正文。

#### Scenario: 合法上下文
- **WHEN** 用户从时间线或建议入口发送带来源和时间范围的问题
- **THEN** 模型收到有界业务上下文，而持久 user message 只保存用户输入与规范化 contextSnapshot

#### Scenario: 超限或敏感上下文
- **WHEN** context 过深、过宽、过大或包含敏感配置字段
- **THEN** 后端拒绝或规范化不安全部分，不把完整配置或密钥注入模型

### Requirement: SPEC-CHAT-TAB-007 跨入口联动
Agent 页“继续追问”等入口 SHALL 切换到会话页、设置本轮 context 并聚焦 composer。入口提供的预填
问题 SHALL 只成为草稿，MUST NOT 在用户确认前自动发送。同一次入口操作 MUST NOT 同时打开 legacy
drawer 和会话页。

#### Scenario: 预填追问
- **WHEN** 上下文入口提供预填问题
- **THEN** 会话页显示可编辑草稿并聚焦输入框，等待用户确认发送

### Requirement: SPEC-CHAT-TAB-008 建议任务操作
assistant 消息中的 `suggestedTasks` SHALL 由服务端规范化并持久化。消息 action 或 fallback 按钮 MAY
把单项建议转换为任务；创建成功后 SHALL 禁用或标记该操作。渲染 MUST 使用服务端消息 ID、规范化
任务和本地转义模板，MUST NOT 执行模型提供的任意 HTML。

#### Scenario: 创建建议任务
- **WHEN** 用户选择 assistant 消息中的一项建议并且任务创建成功
- **THEN** 对应 action 被标记为已处理，重复点击不会再次创建同一操作

#### Scenario: 不可信模型标记
- **WHEN** suggested task 文本包含 HTML 或脚本片段
- **THEN** 页面按文本或本地转义模板渲染，不执行模型标记

### Requirement: SPEC-CHAT-TAB-009 上下文与长期记忆入口
上下文区域 SHALL 显示本轮来源、时间范围和用户选择的信息，MUST NOT 回显配置敏感值。会话 SHALL
提供记忆策略及候选管理入口，并遵循长期记忆能力的确认和安全规则。删除会话 MUST NOT 隐式删除已独立
保存的长期记忆。

#### Scenario: 查看本轮上下文
- **WHEN** 当前请求来自时间线、建议或记忆入口
- **THEN** 页面显示有界来源摘要和时间范围，不显示密钥或完整配置

#### Scenario: 删除会话
- **WHEN** 用户删除一个已有独立长期记忆的会话
- **THEN** 会话及其模型状态按删除流程处理，独立长期记忆继续保留

### Requirement: SPEC-CHAT-TAB-010 LLM 不可用时的降级
状态接口显示 LLM 未配置或不可用时，页面 SHALL 禁用发送并提供可操作提示；会话浏览、搜索、删除和
记忆管理 SHALL 继续可用。页面 MUST NOT 因模型不可用永久 loading 或静默丢弃输入。

#### Scenario: LLM 不可用
- **WHEN** 桌面状态报告模型未配置或不可用
- **THEN** composer 发送被禁用并显示配置提示，其它本地会话操作仍可使用

### Requirement: SPEC-CHAT-TAB-011 失败、重试与 pending 恢复
持久化前失败时，页面 SHALL 保留输入草稿且不伪造消息。user/pending 已持久化后失败时，原 pending
SHALL 更新为 error；重试 MUST 复用原 session/user/pending/context ID，不追加重复 user turn。空或
非字符串成功响应、502、SSE error、网络或解析中断均 SHALL 保留重试入口，不得写成功占位文本。
刷新后只允许恢复最新未完成 turn；若 AgentState 已有 terminal 回复，系统 SHALL 返回既有结果而不再次
调用模型。busy SHALL 返回 409。sent PUT 与确认 GET 均失败时，页面 SHALL 进入 reconciliation-required，
并在 canonical GET 成功前冻结新发送。

#### Scenario: 持久化前失败
- **WHEN** user/pending 追加失败
- **THEN** 输入保留为草稿，页面不创建本地成功消息

#### Scenario: 已持久化 turn 失败
- **WHEN** user/pending 已保存但模型、网络或 canonical 更新失败
- **THEN** 原 pending 转为 error，重试仍使用原 turn ID

#### Scenario: 重载 pending 且已有 terminal
- **WHEN** 页面恢复 pending，但 AgentState 已保存同一 user turn 的 terminal assistant
- **THEN** 后端返回既有结果，不再次调用模型或工具

#### Scenario: reconciliation required
- **WHEN** terminal assistant 的 sent 更新和后续 canonical GET 都失败
- **THEN** 页面冻结该会话的新发送，直到 canonical GET 成功

### Requirement: SPEC-CHAT-TAB-012 键盘、焦点与内容安全
桌面 composer SHALL 使用 Enter 发送、Shift+Enter 换行，并在输入法组合期间忽略 Enter 发送。进入
会话页或从上下文入口进入后 SHALL 聚焦 composer。交互按钮 MUST 可键盘操作并具有可读标签；
disabled/loading/error MUST NOT 只依赖颜色表达。用户和模型纯文本 SHALL 按文本节点或关闭 raw HTML
的 Markdown 渲染；结构化 HTML 只能由本地转义模板生成。

#### Scenario: 键盘发送与输入法
- **WHEN** 用户按 Enter、Shift+Enter 或在输入法组合期间按 Enter
- **THEN** 页面分别发送、插入换行或保持输入，不误发送组合文本

#### Scenario: 模型文本包含 HTML
- **WHEN** 模型文本包含 HTML 标签或事件属性
- **THEN** 消息面不执行 raw HTML，结构化操作仍由本地模板生成
