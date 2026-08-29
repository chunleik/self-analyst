# SelfAnalyst 会话服务契约 SDD 规格说明书

> 本文档定义会话/消息 REST、数据模型、前端接线、AgentState 路由和资源不变量。物理存储当前为
> SQLite `chat.db`；表结构、迁移和 WAL 语义见
> [`chat-session-sqlite-store.md`](chat-session-sqlite-store.md)。

## 1. 文档元信息

| 属性 | 值 |
|------|-----|
| 功能 | 后端会话持久化、REST CRUD、AgentState、分页搜索、摘要和删除协调 |
| 状态 | 已实现（当前对外契约） |
| 规格前缀 | `SPEC-CSP-*` |
| 权威正文 | `{memoryDir}/chat-sessions/chat.db` |
| 模型历史 | `{memoryDir}/agent-state/self-analyst-chat/desktop/<sessionId>/` |

## 2. 与历史方案的关系

- **SPEC-CSP-DEC-001**：会话和消息由 localhost 后端持久化并提供细粒度 REST；浏览器
  `localStorage` 不再是权威来源。
- **SPEC-CSP-DEC-002（已修订）**：`chat.db` 是 UI transcript 权威，AgentState 是模型历史权威；
  旧“每会话 shard 是正文权威”由 `SPEC-CSS-*` 取代。
- **SPEC-CSP-DEC-008/-015/-016/-017（已取代）**：旧分片布局、`index.state`、派生索引和文件
  tombstone 协议只属于迁移来源，不是当前运行时设计。
- **SPEC-CSP-API-010（已取代）**：当前库内原子性由 SQLite WAL 单事务提供，见 `SPEC-CSS-API-001`。

## 3. 当前设计决策

- **SPEC-CSP-DEC-003**：所有写入使用按会话/消息的 REST CRUD，不接受前端整库 blob 覆盖。
- **SPEC-CSP-DEC-004**：不迁移 WebView `selfAnalyst.chatSessions.v1`；前端停止读取并清理旧 key。
- **SPEC-CSP-DEC-005**：会话数量不限；单会话最多 200 条消息，并按完整 user turn 淘汰；单条
  `content` 最多 20,000 Unicode code point，截断后追加 `...`。
- **SPEC-CSP-DEC-006**：active session 在后端 metadata 中持久化。
- **SPEC-CSP-DEC-007**：会话正文和 AgentState 在本机明文保存；不得写入 API key 或完整用户配置。
- **SPEC-CSP-DEC-009**：搜索范围为 `title + summary + lastMessagePreview`，不索引消息全文；
  ≥3 code point 使用 FTS5 trigram，短查询/FTS 失败回退字面量 LIKE。
- **SPEC-CSP-DEC-010**：会话摘要异步生成；LLM 不可用、预算受限或失败时使用确定性摘要，不阻断收发。
- **SPEC-CSP-DEC-011**：旧服务端 transcript 已迁移到 `chat.db`、但 AgentState 不存在时，下一次发送
  只导入当前 user 之前的有效 user/sent assistant，一次成功后不重复导入。
- **SPEC-CSP-DEC-012a**：模型历史达到阈值后按 `agent-context-compaction.md` 事务压缩；业务
  `contextSnapshot` 只通过 RuntimeContext 临时注入。
- **SPEC-CSP-DEC-012b**：相同 `userMessageId` 已有 terminal assistant 时直接返回已有回复；未完成时
  继续原 turn，不追加重复 user。聊天、取消和删除共享 Agent 生命周期协调。
- **SPEC-CSP-DEC-013**：`title/contextLabel/summary/error`、opaque JSON、建议任务、消息批次和单会话
  序列化结果都有服务端预算；opaque 超限时保留识别字段并加 `_truncated`。
- **SPEC-CSP-DEC-014**：chat/session mutation body 最大 256 KiB，JSON 最大深度 32；错误 shape、
  空 batch、null message 和非法 role/status 在事务前拒绝。
- **SPEC-CSP-DEC-018**：生产 DesktopServer 使用 `.writer.lock` 持有进程级独占 lease；同一
  `memoryDir` 的第二个 writer fail fast。

## 4. 目标

- **SPEC-CSP-GOAL-001..004**：会话在重启后仍存在；REST 覆盖列表、读取、创建、元数据、删除、
  消息追加/更新和 active；失败返回可读错误且不产生部分库内 mutation。
- **SPEC-CSP-GOAL-005**：会话数不限，但消息、字段、请求和单会话序列化成本有界。
- **SPEC-CSP-GOAL-006**：摘要和元数据搜索不阻断消息收发。
- **SPEC-CSP-GOAL-007**：每个会话对应独立、可恢复的 AgentState。
- **SPEC-CSP-GOAL-008**：pending 可用原 ID 恢复，重复请求不重复调用模型；冲突统一返回 409。

## 5. 数据模型

### SessionMeta / Session

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | string | 服务端生成 lowercase hex32 |
| `title` | string | 默认“新会话” |
| `createdAt` / `updatedAt` | ISO-8601 | 服务端维护 |
| `source` | string | `manual`、`agent_context`、`task_context` 等 |
| `contextLabel` | string? | 可选上下文标签 |
| `contextSnapshot` | object? | 完整 Session 返回，可按预算规范化 |
| `memoryPolicy` | string | 会话长期记忆策略 |
| `summary` | string? | 异步摘要，可滞后 |
| `lastMessagePreview` | string? | 列表/搜索预览 |
| `messageCount` | number | 当前消息数 |
| `messages` | Message[] | 仅完整 Session 返回，按 seq/时间升序 |

### Message

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | string | 服务端生成 lowercase hex12 |
| `role` | string | `user`、`assistant`、`system` |
| `content` | string | 服务端裁剪后的 canonical 文本 |
| `createdAt` | ISO-8601 | 服务端生成 |
| `status` | string? | `pending`、`sent`、`error` |
| `error` | string? | 错误文案 |
| `contextSnapshot` | object? | 发送时业务上下文 |
| `suggestedTasks` | object[]? | 规范化建议任务 |

- **SPEC-CSP-MODEL-001**：会话和消息 ID 只由服务端生成；客户端提供的 ID 不得成为权威。
- **SPEC-CSP-MODEL-002**：时间戳、摘要和派生预览由服务端维护。
- **SPEC-CSP-MODEL-003**：opaque 字段不解释业务含义，但必须执行资源规范化，不承诺超限值逐字 round-trip。
- **SPEC-CSP-MODEL-004**：读取时忽略未知字段，保持向前兼容。
- **SPEC-CSP-MODEL-005（已修订）**：`chat.db` 是正文与元数据单一权威；损坏库先备份，存在
  `legacy/` 时尝试重建，否则记录 error 并以空库启动，不得静默覆盖恢复证据。

## 6. REST 契约

接口前缀为 `/desktop/chat/sessions`，错误统一为 `{ "error": <string> }`。

### SPEC-CSP-API-001：列表、分页和搜索

- 无查询参数时返回兼容结构 `{ activeSessionId, sessions }`，列表项不含 `messages`。
- 提供 `limit/cursor/q` 时返回 `{ activeSessionId, sessions, nextCursor, hasMore }`；limit 默认 50，
  范围 1..200。
- 排序为 `updatedAt DESC, id DESC`。cursor 绑定规范化 query、anchor 与 generation；过期 cursor
  返回 400，前端重载首屏。

### SPEC-CSP-API-002：读取单会话

`GET /desktop/chat/sessions/{id}` 返回包含 messages 的完整 Session。未知 ID 返回 404，非法 ID 返回 400。

### SPEC-CSP-API-003：创建

`POST /desktop/chat/sessions` 接受可选 title/source/contextLabel/contextSnapshot/initialMessages；服务端
生成全部 ID 和时间戳，创建后设置 active，返回 201 与完整 Session。

### SPEC-CSP-API-004：更新元数据

`PUT /desktop/chat/sessions/{id}` 更新允许的元数据并返回 canonical Session；不得通过该接口替换 messages。

### SPEC-CSP-API-005：删除

- gate 忙时返回 409，且不得先写 durable intent。
- `beginDeletion` 在 `pending_deletions` 写 intent；随后删除 cache、AgentState 和 transcript，最后清 intent。
- intent 后中断时，重试或下次启动幂等补齐；即使 Agent 未初始化也必须直接清理持久 AgentState。
- 删除 active 后重选剩余最新会话；独立长期记忆不随会话删除。

### SPEC-CSP-API-006：追加消息

接受单条 Message 或 `{ messages: [...] }`，支持一次追加 user + pending assistant；服务端分配 ID、
规范化、裁剪并在单事务写入，返回 201 canonical 消息并异步触发摘要。

### SPEC-CSP-API-007：更新消息

更新 content/status/error/suggestedTasks。主要状态流为 pending → sent/error；重试可把原 error 恢复
为 pending。不得把 sent assistant 改成晚到 error，也不得通过该接口修改 user 生命周期。

### SPEC-CSP-API-008：active 指针

`PUT /desktop/chat/active-session` 接受 `{ activeSessionId: string|null }`；非空 ID 必须存在。

### SPEC-CSP-API-009：资源不变量

所有 create/update/append/summary 路径都执行 DEC-005/-013/-014。最终 retention 以完整 user turn 为
单位；不得删除 user 锚点却保留 orphan assistant。任一最新唯一 turn 本身无法满足硬上限时整次拒绝。

### SPEC-CSP-API-010（已取代）

旧分片/`index.state` 恢复协议不再运行；当前原子性、损坏恢复和迁移见 `SPEC-CSS-API-*`。

### SPEC-CSP-API-011：摘要

消息发生实质变化后异步 debounce 生成摘要；LLM 失败时使用确定性摘要。调用计入预算，输入只包含
会话内容，不包含配置敏感值。

### SPEC-CSP-API-012：Agent 路由、stream 与幂等

- 会话聊天请求必须包含 `sessionId + userMessageId`，且 user ID 必须属于该会话。
- `/desktop/chat` 与 `/desktop/chat/stream` 使用数据库中的 canonical user content/contextSnapshot，
  按 `(userId=desktop, sessionId)` 选择 AgentState。
- 相同 user turn 已完成时返回已有 terminal assistant；gate 冲突返回 409，不追加模型历史。
- `/desktop/chat/sessions/{id}/cancel` 只取消匹配的当前执行，不得影响其它会话。

## 7. 前端契约

- **SPEC-CSP-FE-001**：`api.js` 提供列表、分页、搜索、读取、创建、更新、删除、消息追加/更新、
  active、stream 和 cancel 方法。
- **SPEC-CSP-FE-002**：初始化加载 50 条元数据；active 不在首屏时单独读取且不改写后端指针。
- **SPEC-CSP-FE-003**：正文按会话懒加载；失败保持 `messagesLoaded=false` 与可重试错误。
- **SPEC-CSP-FE-004**：所有 mutation 调用细粒度 REST；发送采用 append user+pending → stream/chat →
  更新同一 pending 的顺序，并使用服务端 canonical 返回。
- **SPEC-CSP-FE-005**：搜索 debounce 200ms；普通/搜索 cursor 分离；request ID 与 mutation generation
  丢弃乱序响应。
- **SPEC-CSP-FE-006**：不读取旧 localStorage，并清理旧 key。
- **SPEC-CSP-FE-007**：持久化前失败保留 composer；409/500/网络错误不能渲染成成功 assistant。
- **SPEC-CSP-FE-008**：会话页始终传 server-owned session/user IDs；legacy drawer 可同时省略两者。
- **SPEC-CSP-FE-009**：不发送 `context.history`；AgentState 是模型历史权威。
- **SPEC-CSP-FE-010**：重试复用原 session/user/pending/context IDs，不追加新 turn。
- **SPEC-CSP-FE-011**：重载后的 pending 提供恢复/重试入口，最终更新原 pending。
- **SPEC-CSP-FE-012**：不提供 history toggle；右侧只显示业务上下文和记忆控制。
- **SPEC-CSP-FE-013**：正文 GET 失败、sent PUT 与确认 GET 都失败时进入 reconciliation-required，
  canonical GET 成功前冻结新发送。

## 8. 非目标

- **SPEC-CSP-NON-001**：会话视觉和键盘行为由 `desktop-chat-tab.md` 约束。
- **SPEC-CSP-NON-002**：不迁移 localStorage 会话。
- **SPEC-CSP-NON-003**：不提供云同步、跨设备协作或账号体系。
- **SPEC-CSP-NON-004**：不提供消息级删除/会话归档，不索引消息正文全文。
- **SPEC-CSP-NON-005**：不改变 tasks/config 等非聊天接口。
- **SPEC-CSP-NON-006（已取代）**：早期“不引入流式输出”限制已废止；当前会话页使用 stream，并保留非流式兼容入口。
- **SPEC-CSP-NON-007**：摘要允许滞后和确定性降级，不保证逐字或实时一致。

## 9. 测试规格

| 规格 ID | 当前含义/状态 |
|---------|---------------|
| SPEC-CSP-TST-001..008 | 空库、列表形状、创建、追加、更新、删除、会话不限量 |
| SPEC-CSP-TST-009（已修订） | mutation 只改变目标会话的数据库行与同事务 metadata，不影响其它会话内容 |
| SPEC-CSP-TST-010..013 | 200 条完整-turn retention、20k code point、404、非法 active 400 |
| SPEC-CSP-TST-014（已取代） | 旧索引重建测试由 `SPEC-CSS-TST-014/-019` 覆盖 |
| SPEC-CSP-TST-015..018 | 摘要降级、搜索、发送顺序、localStorage 清理 |
| SPEC-CSP-TST-019..029 | AgentState 隔离/迁移/恢复、busy、删除、压缩事务安全 |
| SPEC-CSP-TST-030..034 | 资源预算、Unicode 裁剪、请求限制、状态机、前端 reconciliation |
| SPEC-CSP-TST-035..037（已取代） | 旧 shard/index.state 恢复，由 SQLite 事务测试覆盖 |
| SPEC-CSP-TST-038..039 | 稳定 keyset 分页、active 不在首屏和搜索乱序 |
| SPEC-CSP-TST-040/-042（已取代） | 旧 state 证据校验，不再适用于当前运行时 |
| SPEC-CSP-TST-041/-043 | generation/cursor 错误与 mutation 期间乱序响应 |
| SPEC-CSP-TST-044..047（已取代） | 文件 tombstone 测试由 `pending_deletions` 表和 `SPEC-CSS-TST-021` 覆盖 |
| SPEC-CSP-TST-048 | 同 memoryDir 双 writer lease |
| SPEC-CSP-TST-049（已取代） | 旧 index.json 迁移由 `SPEC-CSS-TST-015` 覆盖 |
| SPEC-CSP-TST-050 | sessions/messages/metadata 单事务与 generation 语义 |
| SPEC-CSP-TST-051 | FTS5/LIKE 搜索、`%`/`_` 字面量和 keyset |
| SPEC-CSP-TST-052（已取代） | 旧派生 index 恢复由 chat.db 损坏恢复覆盖 |

## 10. 追溯矩阵

| 规格 ID | 文件/组件 | 验证 |
|---------|-----------|------|
| SPEC-CSP-DEC/GOAL/MODEL-* | `ChatSessionStore.java`、本 spec、`chat-session-sqlite-store.md` | 代码审查、store 测试 |
| SPEC-CSP-API-001..009 | `DesktopChatSessionController.java`、`ChatSessionStore.java`、`DesktopServer.java` | controller/store 测试 |
| SPEC-CSP-API-010 | `chat-session-sqlite-store.md` | SQLite 迁移/事务测试 |
| SPEC-CSP-API-011 | `ChatSummaryService.java`、`DesktopChatSessionController.java` | 摘要测试 |
| SPEC-CSP-API-012 | `DesktopAgentController.java`、`SelfAnalystAgent.java` | lifecycle、stream、幂等测试 |
| SPEC-CSP-FE-001..013 | `desktop-ui/api.js`、`chat.js`、`deep-chat-adapter.js`、`state.js` | Node 测试、静态检查 |
| SPEC-CSP-TST-* | chat store/controller/Agent session/JS 测试 | `mvn test`、Node 测试 |
