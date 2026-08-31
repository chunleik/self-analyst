# 聊天会话服务与存储规格

## Purpose

定义桌面聊天会话的本地权威数据、REST CRUD、资源预算、AgentState 路由与幂等、摘要、删除协调，以及 SQLite 单库事务、搜索、旧数据迁移和损坏恢复行为。

## Requirements

### Requirement: SPEC-CSP-DEC-001、002、003、004 会话权威与写入边界
会话和消息 SHALL 由 localhost 后端通过细粒度 REST 持久化；客户端 MUST NOT 使用整库 blob 覆盖。
`chat.db` SHALL 是 UI transcript 和会话元数据的唯一权威，AgentState SHALL 是模型历史权威。
浏览器旧 `selfAnalyst.chatSessions.v1` localStorage MUST NOT 被迁移或重新成为权威，前端 MAY 清理该 key。

#### Scenario: 服务端会话权威
- **WHEN** 客户端创建、修改或删除会话与消息
- **THEN** mutation 通过对应 REST 作用于 `chat.db`，前端缓存只使用 canonical 返回更新

#### Scenario: 旧浏览器会话
- **WHEN** WebView localStorage 中存在旧聊天 key
- **THEN** 当前会话列表不读取或导入该数据，并 MAY 清理旧 key

### Requirement: SPEC-CSP-DEC-005、007、013、014、018 有界本地存储与单 writer
会话数量 SHALL 不设产品级上限；单会话最多保留 200 条消息，并以完整 user turn 为单位淘汰。单条
content 最多 20,000 Unicode code point，超限 SHALL 截断并追加 `...`。标题、标签、摘要、错误、
opaque JSON、建议任务、消息批次和单会话序列化结果 SHALL 有服务端预算。mutation body MUST 不超过
256 KiB，JSON 深度 MUST 不超过 32。会话正文与 AgentState 在本机明文保存，但 MUST NOT 写入 API key
或完整用户配置。生产服务 SHALL 持有同一 memoryDir 的独占 writer lease，第二个 writer MUST fail fast。

#### Scenario: 完整 user turn 裁剪
- **WHEN** 新消息使会话超过 200 条或单会话序列化预算
- **THEN** 系统优先淘汰最旧完整 user turn，不留下 orphan assistant；最新唯一 turn 自身仍超限时整次拒绝

#### Scenario: 请求超限
- **WHEN** mutation body 超过 256 KiB、JSON 过深、消息 shape 非法或 batch 为空
- **THEN** 系统在事务前返回客户端错误，不产生部分 mutation

#### Scenario: 双 writer
- **WHEN** 第二个生产实例尝试以同一 memoryDir 打开会话存储
- **THEN** 第二个实例立即失败，不并发写入同一 `chat.db`

### Requirement: SPEC-CSP-DEC-006、SPEC-CSP-GOAL-001..004 active 指针与持久 CRUD
active session SHALL 在后端 metadata 中持久化。REST SHALL 支持列表、读取、创建、元数据更新、删除、
消息追加与更新、摘要写回和 active 指针更新；重启后会话和 active 指针 SHALL 恢复。非空 active ID
MUST 引用现有会话。

#### Scenario: 重启恢复
- **WHEN** 会话和 active 指针已提交后服务重启
- **THEN** 列表、完整正文和 active session 从 `chat.db` 恢复

#### Scenario: 非法 active 指针
- **WHEN** 客户端把 activeSessionId 设置为不存在或非法 ID
- **THEN** 系统返回客户端错误，原 active 指针保持不变

### Requirement: SPEC-CSP-DEC-009、010、SPEC-CSP-GOAL-006 搜索与摘要不阻断收发
会话搜索 SHALL 只覆盖 title、summary 和 lastMessagePreview，不索引消息全文。查询达到 3 code point
时 MAY 使用 FTS5 trigram；短查询或 FTS 不可用时 SHALL 回退字面量 LIKE。会话摘要 SHALL 异步 debounce
生成；LLM 不可用、预算受限或失败时 SHALL 使用确定性摘要，且 MUST NOT 阻断消息收发。

#### Scenario: 元数据搜索
- **WHEN** 用户搜索标题、摘要或最后消息预览中的子串
- **THEN** 系统返回匹配会话，不因消息正文中单独出现该词而命中

#### Scenario: 摘要服务失败
- **WHEN** LLM 摘要不可用或调用失败
- **THEN** 会话 mutation 已提交，后台使用确定性摘要并允许继续聊天

### Requirement: SPEC-CSP-DEC-011、012a、012b、SPEC-CSP-GOAL-007..008 AgentState 路由与同 turn 幂等
每个会话 SHALL 使用 `(userId=desktop, sessionId)` 隔离 AgentState。旧 transcript 已存在但 AgentState
缺失时，下一次发送 MAY 一次性导入当前 user 之前的有效 user/sent assistant；成功后不得重复导入。
模型历史达到阈值时 SHALL 事务性压缩，失败时保留原状态。相同 `userMessageId` 已有 terminal assistant
时 SHALL 返回已有回复；未完成时 SHALL 继续原 turn，不追加重复 user。聊天、取消和删除 SHALL 共享
同一会话执行协调，busy 冲突返回 409。

#### Scenario: AgentState 会话隔离
- **WHEN** 两个会话分别发送消息
- **THEN** 模型历史写入各自 sessionId，互不混入

#### Scenario: 已完成 turn 重放
- **WHEN** 相同 sessionId 和 userMessageId 已有 terminal assistant
- **THEN** 系统直接返回已持久化回复，不再次调用模型或工具

#### Scenario: 压缩失败
- **WHEN** AgentState 摘要或原子保存失败
- **THEN** 系统保留压缩前模型历史和 rolling summary，不提交部分压缩状态

### Requirement: SPEC-CSP-MODEL-001..005 会话与消息模型
会话 ID SHALL 由服务端生成 lowercase hex32，消息 ID SHALL 由服务端生成 hex12；客户端 ID MUST NOT
成为权威。会话 SHALL 提供 title、createdAt、updatedAt、source、contextLabel、contextSnapshot、
memoryPolicy、summary 和 messages；消息 SHALL 提供 role、content、status、error、createdAt、
contextSnapshot 和 suggestedTasks。时间戳、摘要、messageCount 与 lastMessagePreview SHALL 由服务端维护。
opaque 字段 SHALL 执行资源规范化，读取时 SHALL 忽略未知字段以保持向前兼容。

#### Scenario: 服务端生成标识和时间
- **WHEN** 客户端创建会话或追加没有权威 ID 的消息
- **THEN** 服务端生成符合格式的 ID 和时间戳，并在 canonical 返回中提供

#### Scenario: 未知读取字段
- **WHEN** 兼容数据包含当前模型不认识的额外字段
- **THEN** 读取忽略未知字段，已知会话与消息内容仍可使用

### Requirement: SPEC-CSP-API-001 列表、分页与搜索
`GET /desktop/chat/sessions` SHALL 支持 `limit`、opaque `cursor` 和可选 `q`，返回 activeSessionId、
sessions、generation、nextCursor 和 hasMore。排序 SHALL 稳定为 `updatedAt DESC, id DESC`。limit MUST
限制在 1..200。cursor SHALL 绑定 query 与 generation；非法 cursor 返回 400，generation 或 query
不匹配返回 410。空白 q SHALL 视为无搜索。

#### Scenario: 稳定 keyset 分页
- **WHEN** 多个会话具有相同 updatedAt 并跨页读取
- **THEN** id 作为稳定决胜键，页面不重复或遗漏会话

#### Scenario: stale cursor
- **WHEN** mutation 改变 generation 后客户端继续使用旧 cursor
- **THEN** 服务端返回 410，客户端从第一页恢复

### Requirement: SPEC-CSP-API-002、003、004 单会话读取、创建与元数据更新
`GET /sessions/{id}` SHALL 返回完整会话或 404。`POST /sessions` SHALL 接受 title、source、contextLabel、
contextSnapshot 和 memoryPolicy，由服务端生成 ID、时间戳及空消息列表。`PUT /sessions/{id}` SHALL
只更新提供的 title/contextLabel/contextSnapshot；请求 MUST NOT 覆盖消息、ID、时间或摘要。

#### Scenario: 创建会话
- **WHEN** 客户端提交合法创建请求
- **THEN** 服务端返回 201 和新会话，messages 为空且 active 指针指向该会话

#### Scenario: 更新会话元数据
- **WHEN** 客户端只提交新的 title 或 context 字段
- **THEN** 服务端更新目标元数据和 updatedAt，其它会话与消息保持不变

### Requirement: SPEC-CSP-API-005 删除协调
`DELETE /sessions/{id}` SHALL 在删除 transcript 前持久化删除 intent，并与当前 Agent 执行 gate 协调；
busy 会话返回 409。删除流程 SHALL 幂等清理 AgentState、会话消息和 active 指针，并在完成后移除 intent。
删除不存在会话 MAY 返回 `deleted=false`。删除会话 MUST NOT 隐式删除独立长期记忆。

#### Scenario: 删除 active 会话
- **WHEN** active 会话没有运行中的 Agent 请求且删除成功
- **THEN** transcript 与 AgentState 被协调删除，active 指针按剩余会话重选或置空

#### Scenario: 删除 busy 会话
- **WHEN** 目标会话正在处理聊天请求
- **THEN** 服务端返回 409，保留会话、AgentState 和删除协调状态

### Requirement: SPEC-CSP-API-006、007 消息追加与更新
`POST /sessions/{id}/messages` SHALL 接受 1..20 条消息；客户端提供 ID 时必须符合 server-generated 格式，
未提供时由服务端生成。role SHALL 为 user/assistant/system，status SHALL 为 pending/sent/error。
追加 SHALL 原子返回新消息数组并更新会话元数据。`PUT /sessions/{id}/messages/{msgId}` SHALL 只更新
content/status/error/suggestedTasks，并执行允许的状态转换；非法角色、状态或转换返回 400。

#### Scenario: 原子追加 user 与 pending
- **WHEN** 客户端一次提交合法 user 和 pending assistant
- **THEN** 两条消息在同一事务中追加并返回，失败时均不写入

#### Scenario: 非法状态转换
- **WHEN** 客户端尝试把 terminal assistant 转回 pending 或修改不存在消息
- **THEN** 系统返回客户端错误或 404，不提交非法状态

### Requirement: SPEC-CSP-API-008 active session 接口
`PUT /desktop/chat/active-session` SHALL 接受 string 或 null。非空 ID 必须存在；成功更新 active 指针
MUST NOT 增加会话内容 generation。

#### Scenario: 清空 active 指针
- **WHEN** 客户端提交 `activeSessionId=null`
- **THEN** 后端持久化空 active 指针，不修改会话正文

### Requirement: SPEC-CSP-API-009 资源不变量
所有 create、update、append 和 summary 路径 SHALL 执行消息数量、Unicode 内容、辅助字段、opaque JSON、
建议任务、请求体与单会话序列化预算。retention SHALL 保留完整 user turn；不得删除 user 锚点并留下
orphan assistant。最新唯一 turn 本身无法满足硬上限时，整个 mutation MUST 被拒绝。

#### Scenario: 超大最新 turn
- **WHEN** 仅保留最新完整 user turn 仍超过单会话硬预算
- **THEN** mutation 失败且原会话保持不变

### Requirement: SPEC-CSP-API-011 异步摘要
消息发生实质变化后，系统 SHALL debounce 调度摘要。摘要输入 SHALL 只包含会话内容，不包含配置敏感值；
调用 SHALL 计入 token 预算。LLM 失败时 SHALL 写入确定性摘要；摘要 MAY 滞后且不保证逐字实时一致。

#### Scenario: 多次快速 mutation
- **WHEN** 同一会话短时间内连续更新消息
- **THEN** 旧摘要任务被取消或合并，最终为最新会话状态生成摘要

### Requirement: SPEC-CSP-API-012 Agent 路由、流、空响应与取消
聊天请求 SHALL 包含 sessionId 和属于该会话的 userMessageId，并使用数据库 canonical user content 与
contextSnapshot 路由 AgentState。模型流 MUST 产生非空 terminal result；terminal 为空但已有非空文本
delta 时，系统 SHALL 原子写回累计 delta 到同一 turn 后返回。无法安全写回或 result/delta 都为空时
SHALL 返回 HTTP 502 或 SSE error，并保持同 turn 可重试。取消接口 SHALL 只取消匹配的当前执行，不得
影响其它会话。

#### Scenario: 空模型结果
- **WHEN** 模型没有 terminal 文本且也没有非空 delta
- **THEN** 系统返回空响应错误，不写入成功占位 assistant

#### Scenario: delta 回写成功
- **WHEN** terminal 文本为空但流已产生非空累计 delta
- **THEN** 系统把 delta 写回同一 AgentState turn 并作为 canonical 结果，重放不再次调用模型

#### Scenario: 会话级取消
- **WHEN** 客户端取消正在执行的指定 session/user turn
- **THEN** 仅匹配执行被取消，其它会话继续运行

### Requirement: SPEC-CSP-FE-001..006 客户端分页、懒加载与细粒度 mutation
桌面客户端 SHALL 提供列表、分页、搜索、读取、创建、更新、删除、消息追加或更新、active、stream 和
cancel 调用。初始化 SHALL 加载 50 条元数据；正文 SHALL 按会话懒加载。所有 mutation SHALL 使用
细粒度 REST，客户端 MUST NOT 读取旧 localStorage 或发送整块 transcript。搜索 debounce SHALL 为
200ms；普通与搜索 cursor、request ID 和 mutation generation SHALL 分离以丢弃乱序响应。

#### Scenario: active 不在首屏
- **WHEN** 后端 active session 不在首批 50 条元数据中
- **THEN** 客户端单独读取该会话，不改写 active 指针

#### Scenario: 正文读取失败
- **WHEN** 单会话 GET 失败
- **THEN** 客户端保持 messagesLoaded=false 和可重试错误，不把空数组当作 canonical 正文

### Requirement: SPEC-CSP-FE-007..013 客户端失败与 reconciliation
持久化前失败 SHALL 保留 composer；user/pending 已落盘后失败 SHALL 更新原 pending 为 error。客户端
MUST 使用 server-owned session/user ID，不发送 `context.history`，重试 MUST 复用原 IDs。重载 pending
SHALL 提供恢复入口并更新原 pending。正文 GET 失败，或 sent PUT 与确认 GET 都失败时 SHALL 进入
reconciliation-required，并在 canonical GET 成功前冻结新发送。

#### Scenario: 同 turn 重试
- **WHEN** 已持久化 turn 的模型请求失败后用户重试
- **THEN** 客户端复用原 session/user/pending/context ID，不追加新 user turn

#### Scenario: sent 更新结果不确定
- **WHEN** sent PUT 和随后 canonical GET 都失败
- **THEN** 客户端冻结该会话的新发送，直到重新读取 canonical 会话成功

### Requirement: SPEC-CSP-NON-001..005、007 功能边界
聊天会话服务 SHALL 保持本地单机能力，不提供云同步、跨设备协作、账号体系、消息级删除或会话归档，
也不索引消息正文全文。视觉和键盘行为由 desktop-chat 能力约束。摘要 MAY 滞后并使用确定性降级。
该服务 MUST NOT 改变 tasks、config 等非聊天接口。

#### Scenario: 正文全文搜索
- **WHEN** 查询词只出现在历史消息正文而不在标题、摘要或最后消息预览中
- **THEN** 会话搜索不因该正文命中

### Requirement: SPEC-CSS-DEC-001、002、SPEC-CSS-API-001 SQLite 单库事务与崩溃恢复
`chat.db` SHALL 以 WAL、`synchronous=FULL` 和外键约束保存会话正文、元数据、active 指针与 generation。
每次库内 mutation SHALL 在一个 SQLite 事务中完成；提交前崩溃由 WAL 回滚，提交后完整可见，不使用
应用层 DIRTY/CLEAN 状态文件。无法取得 writer lease 时 MUST fail fast。

#### Scenario: 事务提交前失败
- **WHEN** mutation 在 SQLite commit 前失败或进程终止
- **THEN** 重启后数据库保持 mutation 前状态，不出现部分会话或消息更新

#### Scenario: 损坏数据库且存在 legacy
- **WHEN** `chat.db` 无法打开或 schema 校验失败，且存在有效 legacy 备份
- **THEN** 系统保留 corrupt 备份并从 legacy 重建当前数据库

#### Scenario: 损坏数据库且没有 legacy
- **WHEN** `chat.db` 损坏且没有可恢复 legacy
- **THEN** 系统保留 corrupt 备份、记录错误并以空库启动

### Requirement: SPEC-CSS-DEC-004 删除 intent 表
删除 intent SHALL 持久化在库内 pending-deletions 记录中。开始删除 SHALL 幂等写 intent；transcript
删除 SHALL 在单事务中删除会话并级联消息、重选 active；完成删除 SHALL 移除 intent。启动恢复 SHALL
读取遗留 intent 并补齐 AgentState 删除。

#### Scenario: 删除中途重启
- **WHEN** intent 已提交但 transcript 或 AgentState 删除尚未全部完成时进程重启
- **THEN** 恢复流程读取 intent 并幂等补齐剩余步骤

### Requirement: SPEC-CSS-DEC-005、SPEC-CSS-API-004 FTS5 与 LIKE 搜索
元数据搜索 SHALL 使用与 sessions 表同事务维护的 FTS5 trigram 索引；规范化查询达到 3 code point 时
SHALL 尝试 MATCH，短查询或 FTS 报错时 SHALL 回退 LIKE。LIKE 中 `%` 和 `_` SHALL 按字面量匹配。
两条路径 SHALL 保持 `updatedAt DESC, id DESC` keyset 排序。

#### Scenario: 中文或英文子串
- **WHEN** 查询至少 3 code point 且 FTS 可用
- **THEN** trigram 搜索返回与元数据子串语义一致的结果，大小写不敏感

#### Scenario: 短查询与 LIKE 元字符
- **WHEN** 查询少于 3 code point 或包含 `%`、`_`
- **THEN** 系统使用转义 LIKE 按字面量匹配

### Requirement: SPEC-CSS-DEC-006、SPEC-CSS-API-003 旧会话存储迁移
当 `chat.db` 不存在且旧 shard 或旧索引存在时，系统 SHALL 在 `chat.db.migrating` 中导入全部 ID 与
文件名匹配且可解析的 shard，并从可读旧索引或会话时间推断 active 指针。完成后 SHALL 原子替换为
`chat.db`，并把旧存储文件移入只读 `legacy/`，后续 mutation MUST NOT 修改 legacy。残留 migrating
文件 SHALL 在下次打开时删除重做。旧删除 tombstone SHALL 转成库内 pending deletion intent。

#### Scenario: 成功迁移旧存储
- **WHEN** 目录只有旧 shard 和可读旧索引
- **THEN** 新 `chat.db` 包含有效会话、消息和 active 指针，旧文件移入 legacy

#### Scenario: 迁移中断
- **WHEN** 进程在 migrating 数据库原子替换前终止
- **THEN** 下次打开删除残留 migrating 文件并从旧来源重新迁移

#### Scenario: 非法旧 shard
- **WHEN** shard 文件名与会话 ID 不匹配或内容无法解析
- **THEN** 该 shard 被跳过，不注入伪造会话

### Requirement: SPEC-CSS-DEC-007、SPEC-CSS-API-002 存储介质透明性
从旧存储切换到 SQLite SHALL 保持未被取代的会话 REST、数据模型、裁剪、ID、状态机、generation、
cursor、摘要、AgentState 和前端行为契约。调用方 MUST NOT 依赖内部表结构、旧 shard 或 legacy 目录。

#### Scenario: 迁移后的 REST 行为
- **WHEN** 客户端对迁移后的会话执行列表、读取、mutation、搜索和 active 操作
- **THEN** JSON 形状与行为不因底层从 shard 切换为 SQLite 而改变
