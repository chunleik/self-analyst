# SelfAnalyst 会话后端持久化 SDD 规格说明书

> Specification-Driven Development spec. 本文档定义桌面端 `会话` tab 的会话/消息数据由 WebView `localStorage` 改为**后端分片文件持久化 + REST CRUD** 的行为契约。实现必须可追溯至本文档中的规格 ID。

---

## 1. 文档元信息

| 属性 | 值 |
|------|-----|
| 功能名称 | 桌面端会话改为后端持久化（按会话分片 + 索引，REST CRUD，会话数不设上限） |
| 文档状态 | Ready for implementation |
| 日期 | 2026-06-28 |
| 目标平台 | SelfAnalyst Tauri 桌面端内嵌 WebView，Windows 优先 |
| 规格前缀 | `SPEC-CSP-*`（Chat Session Persistence） |
| 主要前端逻辑 | `self-analyst-app/src/main/resources/desktop-ui/`（chat/api/state/init.js） |
| 主要后端逻辑 | `self-analyst-app/src/main/java/com/selfanalyst/desktop/controller/DesktopChatSessionController.java`（新增） |
| 路由注册 | `self-analyst-app/src/main/java/com/selfanalyst/desktop/DesktopServer.java` |
| 会话存储 | `self-analyst-app/src/main/java/com/selfanalyst/desktop/store/ChatSessionStore.java`（新增，`{memoryDir}/chat-sessions/` 目录：`index.json` + 每会话一个分片文件，UTF-8，原子写） |

---

## 2. 背景与当前状态

桌面端 `会话` tab（见 [`desktop-chat-tab.md`](desktop-chat-tab.md)）第一版把全部会话与消息保存在 WebView `localStorage`，key 为 `selfAnalyst.chatSessions.v1`，结构为 `{ version, activeChatSessionId, sessions[] }`。`chat.js` 的 `loadChatSessions()` / `saveChatSessions()` 同步读写该 key。

`localStorage` 持久化的局限：

- 数据仅存于当前 WebView 数据目录，清缓存 / 换设备 / 换 WebView profile 即丢失，且不在用户可见的 `{memoryDir}` 数据目录内（与 `tasks.json`、`config.properties` 不一致）。
- 后端无法访问会话历史，未来若要做会话级摘要、检索、跨入口联动都缺数据源。

用户诉求：**把会话改为后端持久化**，落在与 `tasks.json` 同级的 `{memoryDir}` 数据目录；采用 REST CRUD 粒度的接口；**会话数量不设上限**；为避免"单文件随会话累积而整文件重写变慢"，存储采用**按会话分片 + 轻量索引**。Phase 2 同时把模型历史切换为按 session 持久化的 AgentState，并修订发送、重试与删除的生命周期语义；布局、视觉、键盘交互和业务上下文字段仍由 `desktop-chat-tab.md` 约束。

后端持久化范式已在 `TaskStore`（`tasks.json`，临时文件 + 原子 rename）与 `UserConfigStore` / `ConfigHistoryStore` 中确立；会话摘要的"LLM 生成 + 确定性兜底 + 异步不阻断"范式已在配置版本历史（`SPEC-CFGUI-VER-DEC-002`）中确立。本特性沿用这两套范式。

---

## 3. 与既有 spec 的关系（取代说明）

- **SPEC-CSP-DEC-001**：本特性取代 `desktop-chat-tab.md` 中旧的 WebView 整块存储与客户端 ID 流程。两份 spec 当前共同约束 Phase 2，不得靠“旧条款另行解释”保留矛盾。具体修订为：
  - 会话数量不再设上限；单会话 200 消息和单条 20000 字符上限由后端强制（`SPEC-CSP-DEC-005`）。
  - 搜索匹配 `title + lastMessagePreview + summary`，不扫描全部历史正文（`SPEC-CSP-DEC-009`）。
  - 会话与消息 ID、创建/更新时间由服务端生成；客户端创建时不生成 ID（`SPEC-CSP-MODEL-001/-002`）。
  - 会话 tab 不提供 history toggle，也不得发送 `context.history`；模型历史由 AgentState 自动恢复。
  - 发送采用“追加 user+pending → `POST /desktop/chat(sessionId,userMessageId)` → 更新同一 pending”的有序流程。
  - 重试和重载后 pending 恢复复用原服务端 IDs；busy 为 HTTP 409；旧服务端 transcript 可单次懒迁移到尚不存在的 AgentState。

---

## 4. 设计结论（决策与取舍）

- **SPEC-CSP-DEC-002**（双权威）：UI 可见的会话正文以 `{memoryDir}/chat-sessions/` 分片为唯一事实来源；模型执行历史以 `{memoryDir}/agent-state/self-analyst-chat/desktop/<sessionId>/` 下的 AgentState（滚动 summary + recent context）为唯一事实来源。前端 `state.chatSessions` 仅是渲染缓存，所有 transcript 写操作必须经 REST 落盘；UI transcript 不得在每轮请求中再次作为模型历史注入。
  - *取舍*：与 `tasks.json` 同目录范式，数据可被后端复用、随用户数据目录迁移；放弃纯前端零依赖（本就是 localhost 单机后端，前后端共生）。

- **SPEC-CSP-DEC-003**：写操作采用 **REST CRUD 粒度**（按会话、按消息的细粒度增删改），**不**采用「前端提交整份文档、后端整块覆盖」的 blob PUT。
  - *取舍*：细粒度接口避免并发整块回写的丢更新，单次 payload 小，与 `TaskStore` CRUD 风格一致。

- **SPEC-CSP-DEC-004**：**不迁移**既有 WebView key `selfAnalyst.chatSessions.v1`，直接切换；前端不再读取并主动删除该旧 key。此决策只针对浏览器存储，不影响 `SPEC-CSP-DEC-011` 对已有服务端 shard 的 AgentState 懒迁移。
  - *取舍*：旧浏览器数据不成为后端事实来源；已经写入服务端的正文则保留并可继续对话。

- **SPEC-CSP-DEC-005**（裁剪上限 / 会话数不限）：**会话数量不设上限**——不再按数量淘汰旧会话。保留两项与单会话体量相关的不变量：单会话最多 **200** 条消息（超出淘汰最旧）、单条消息 `content` ≤ **20000** 字符（超出截断并追加 `...`）。两项均由**后端**在写入时强制执行。
  - *取舍*：取消会话数上限满足用户诉求；保留单会话两项上限以约束**单个分片文件**体量，使任一次写仍是有界成本。

- **SPEC-CSP-DEC-006**：`active session` 指针随索引一并持久化于后端，使「上次选中的会话」在刷新 / 重启后端后仍能恢复。

- **SPEC-CSP-DEC-007**：会话内容以**明文**存于 `{memoryDir}/chat-sessions/`，与 `tasks.json` 一致；后端仅绑定 `localhost`。不得把 `llm.api-key` 等配置敏感值写入这些文件。

- **SPEC-CSP-DEC-008**（分片存储布局）：会话**按会话分片**存储，避免"全部会话放一个文件、每次写整文件重写"导致写成本正比于历史总量。布局：

  ```text
  {memoryDir}/chat-sessions/
  ├── index.json            # activeSessionId + 每个会话的元信息投影（含摘要/预览/消息数）
  ├── <sessionId>.json      # 单个会话的完整数据（含全部 messages）
  └── ...
  ```

  - **分片文件**是单个会话 UI 可见 transcript 的**事实来源**；**index.json** 是从各分片派生的**投影/缓存**（用于列表与搜索），可在损坏/缺失时由分片重建。模型历史的事实来源另为 AgentState（SPEC-CSP-DEC-002）。
  - 写一条消息只重写**该会话的分片**（≤200 条，有界）+ 更新 **index.json 中对应一行**。写成本与历史会话总数无关。
  - *取舍*：index.json 仍随会话数线性增长，但只含**元信息**（无消息正文），量级约为"会话数 × 数百字节"，远小于全量；其重写发生在会话元信息变化（新建/删除/标题或摘要/`updatedAt` 变更）时。这是为"会话数不限"换取的可接受成本。

- **SPEC-CSP-DEC-009**（会话搜索数据来源）：会话搜索为**前端对 index.json 各会话条目的本地过滤**，匹配字段为 **title + 最后一条消息预览 + 会话摘要**（`summary`）。不把全部历史消息正文加载到前端、也不在每次查询时扫描分片文件。
  - *取舍*：会话数不限后，"前端加载全部消息正文做全文搜索"不可行、"每次查询扫盘"会随会话增多变慢；改为搜索一份**压缩进索引的摘要**，兼顾零扫盘与"能搜到聊过的内容"。代价：搜索召回受摘要质量限制，非逐字全文。

- **SPEC-CSP-DEC-010**（会话摘要生成）：每个会话维护一段**简短摘要**（约一句话），作为搜索的"正文代理"并可用于展示提示。摘要由 **LLM 基于会话内容生成**，**LLM 不可用 / 预算受限（`SPEC-BUDGET-*`）/ 失败**时回退到**确定性兜底摘要**（由会话内若干用户消息片段拼接得到）。生成是**异步、best-effort、不得阻断**消息收发；后端可对再生成做节流/合并。
  - *隐私*：摘要的 LLM 输入是**会话自身内容**——该内容在聊天时本就发送给同一 LLM 端点，故不引入新泄露面；但摘要输入**绝不**包含配置敏感值（与 `SPEC-CSP-DEC-007` 一致）。摘要生成计入 LLM 用量并受预算约束。

- **SPEC-CSP-DEC-011**（旧服务端 transcript 单次懒迁移）：若服务端 shard 已有历史而 `(desktop, sessionId)` AgentState 尚不存在，后端在该会话下一次聊天请求取得 Agent 生命周期 gate 后、处理当前 user turn 前，使用当前 `userMessageId` 定位边界并 seed 一次。只导入边界之前有效的 user 与非 pending/error assistant，排除 system/UI notice；AgentState 已存在或已有内容时不得再次导入。
- **SPEC-CSP-DEC-012**（有界模型历史）：达到 message/token 阈值后按 [`agent-context-compaction.md`](agent-context-compaction.md) 事务压缩旧前缀；本轮 `contextSnapshot` 仅通过 RuntimeContext 临时注入，不写入 AgentState user Msg。

- **SPEC-CSP-DEC-012**（有序、幂等与生命周期 gate）：发送和重试由服务端 ID 串联。相同 `userMessageId` 已有 terminal assistant 时直接返回既有回复，不再次调用模型；只有 user turn 尚未完成时才继续。聊天与删除共享同一 application-wide Agent 生命周期 gate；gate 忙返回 409，不排队，也不做部分状态变更。

---

## 5. 目标

- **SPEC-CSP-GOAL-001**：会话与消息持久化到 `{memoryDir}/chat-sessions/`，刷新页面、重启后端后会话历史仍在。
- **SPEC-CSP-GOAL-002**：提供按会话、按消息的 REST CRUD 接口，覆盖列表（索引）、单会话读取、创建会话、更新会话元信息、删除会话、追加消息、更新消息（pending→sent/error）、设置 active 会话。
- **SPEC-CSP-GOAL-003**：`会话` tab 的布局与主要交互保持不变；存储通道、搜索、ID 所有权、模型历史、发送/重试顺序和 busy/delete 语义按本 spec 调整。
- **SPEC-CSP-GOAL-004**：写入须原子落盘，任一接口失败须返回可读错误，且不破坏已落盘文件。
- **SPEC-CSP-GOAL-005**：会话数不设上限；单会话消息数与单条消息长度上限由后端强制。
- **SPEC-CSP-GOAL-006**：每个会话维护可搜索的摘要，支撑"按聊过的内容搜会话"，且其生成不阻断收发、可在无 LLM/预算时降级。
- **SPEC-CSP-GOAL-007**：每个服务端会话对应独立、可重启恢复的 AgentState；旧服务端 transcript 在状态缺失时安全地单次懒迁移。
- **SPEC-CSP-GOAL-008**：重载后遗留 pending 可用原 IDs 恢复；重复请求不会重复模型调用或 user turn，Agent 忙统一返回 409。

---

## 6. 数据模型

### 6.1 存储布局（见 SPEC-CSP-DEC-008）

- 目录：`{memoryDir}/chat-sessions/`
- 索引：`index.json` —— `{ "activeSessionId": <string|null>, "sessions": [ <SessionMeta> ... ] }`
- 分片：`<sessionId>.json` —— 单个 `Session`（含 `messages`）

### 6.2 `SessionMeta`（index.json 中的投影项）

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | string | 会话 id |
| `title` | string | 会话标题 |
| `createdAt` | string(ISO-8601) | 创建时间 |
| `updatedAt` | string(ISO-8601) | 最近写入时间（列表按此降序） |
| `source` | string | `manual` \| `agent_context` \| `task_context` |
| `contextLabel` | string? | 可选上下文标签 |
| `summary` | string? | 会话摘要（搜索字段，可能滞后；见 SPEC-CSP-API-011） |
| `lastMessagePreview` | string? | 最后一条消息的截断预览（搜索/展示用） |
| `messageCount` | number | 当前消息条数 |

### 6.3 `Session`（分片文件，沿用 `desktop-chat-tab.md` §8.2 并补充）

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | string | 服务端生成，唯一且**文件系统安全**（无路径分隔符等） |
| `title` | string | 新建默认 `新会话` |
| `createdAt` / `updatedAt` | string(ISO-8601) | 服务端写入 |
| `source` | string | 同上 |
| `contextLabel` | string? | 可选 |
| `contextSnapshot` | object? | 可选上下文快照（不透明可序列化对象） |
| `summary` | string? | 会话摘要（与 `SessionMeta.summary` 同源） |
| `messages` | Message[] | 消息列表，时间升序 |

### 6.4 `Message`（沿用 `desktop-chat-tab.md` §8.3）

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | string | 服务端生成 |
| `role` | string | `user` \| `assistant` \| `system` |
| `content` | string | 文本；超 20000 字符服务端截断追加 `...`（SPEC-CSP-DEC-005） |
| `createdAt` | string(ISO-8601) | 服务端生成 |
| `status` | string? | `pending` \| `sent` \| `error` |
| `error` | string? | 错误文案 |
| `contextSnapshot` | object? | 发送时上下文快照 |
| `suggestedTasks` | object[]? | Agent 建议任务（结构见 `desktop-chat-tab.md` §8.5） |

### 6.5 约束

- **SPEC-CSP-MODEL-001**：`id`（会话与消息）一律由**服务端**生成并返回，客户端提交的 `id` 被忽略；会话 `id` 必须文件系统安全（作为分片文件名）。
- **SPEC-CSP-MODEL-002**：`createdAt` / `updatedAt` / `summary` 由服务端写入；客户端提交值被忽略。
- **SPEC-CSP-MODEL-003**：`contextSnapshot` / `suggestedTasks` 作为不透明可序列化对象原样存取，后端不校验其内部结构。
- **SPEC-CSP-MODEL-004**：未知字段读取时忽略、不报错（`@JsonIgnoreProperties(ignoreUnknown=true)` 范式）。
- **SPEC-CSP-MODEL-005**：`index.json` 是派生投影；当其缺失或不可解析时，后端**应**能从分片文件重建索引，不得因索引损坏丢失会话正文。

---

## 7. 后端接口契约

接口注册于共享 Javalin 实例，前缀 `/desktop/chat/sessions`（active 指针除外）。错误统一返回 `{ "error": <string> }` 与对应 HTTP 状态码（沿用 `DesktopServer` 异常处理范式）。

### SPEC-CSP-API-001：`GET /desktop/chat/sessions`（列出索引）

- 返回 `{ "activeSessionId": <string|null>, "sessions": [ <SessionMeta> ... ] }`——**仅元信息投影，不含 `messages`**。
- `sessions` 按 `updatedAt` **降序**（newest-first），与会话列表展示顺序一致（`SPEC-CHAT-TAB-004`）。
- 目录/索引不存在时返回 `{ "activeSessionId": null, "sessions": [] }`，HTTP 200。
- 用途：前端初始化时一次性加载列表 + 搜索字段（title/preview/summary），不加载任何消息正文。

### SPEC-CSP-API-002：`GET /desktop/chat/sessions/{id}`（单会话全量）

- 返回该 `Session`（**含 `messages`**），用于会话被打开/激活时按需加载正文。
- `id` 不存在返回 HTTP 404。
- 路由 ID 必须是服务端生成的 lowercase hex32；非法/路径穿越 ID 返回 HTTP 400。

### SPEC-CSP-API-003：`POST /desktop/chat/sessions`（创建会话）

- 请求体：`{ "title"?, "source"?, "contextLabel"?, "contextSnapshot"?, "initialMessages"?: [<Message>] }`，均可缺省。
- 服务端：生成文件系统安全的 `id`、`createdAt`、`updatedAt`；`title` 缺省 `新会话`；`source` 缺省 `manual`；`initialMessages` 逐条分配 `id`/`createdAt` 后写入。
- 创建后该会话成为 `activeSessionId`（SPEC-CSP-DEC-006）。
- 写入新分片文件并更新 index.json。返回 HTTP 201 与创建后的完整 `Session`。
- **不**做会话数量裁剪（SPEC-CSP-DEC-005）。

### SPEC-CSP-API-004：`PUT /desktop/chat/sessions/{id}`（更新会话元信息）

- 请求体部分字段：`{ "title"?, "contextLabel"?, "contextSnapshot"? }`；仅更新提交的非空字段（重命名、首条消息后回填标题、绑定上下文）。
- 更新 `updatedAt`，同步 index.json 对应项；返回更新后的 `Session`；`id` 不存在返回 404。
- **不**通过本接口改 `messages`（走 API-006/007）。

### SPEC-CSP-API-005：`DELETE /desktop/chat/sessions/{id}`（删除会话）

- 删除必须作为一个 Agent 生命周期 gate 内的有序操作完成：清除 ReActAgent cache → 删除 `(desktop, sessionId)` 的完整 AgentState → 删除会话分片并更新 index.json；中途不得释放 gate，使聊天请求无法在隐藏状态删除后、可见正文删除前插入。
- gate 已被聊天占用时返回 HTTP 409 与 `{ "error": "Session is currently processing a chat request" }`，且 AgentState、分片和索引都保持不变。
- 即使 LLM 未配置或 `SelfAnalystAgent` 未初始化，仍须直接打开 `{memoryDir}/agent-state/self-analyst-chat/` 对应的 `AgentStateStore` 清除持久化状态，再删除可见 transcript；不得因 `agent == null` 遗留隐藏状态。
- 已经提取为独立长期记忆的条目保留，由用户在记忆面板单独审阅/删除；删除会话不隐式删除已确认的长期记忆。
- 若删除的是当前 `activeSessionId`，服务端把 active 重选为剩余会话中 `updatedAt` 最新者；无剩余则置 `null`。
- 返回 `{ "deleted": true, "id": <id>, "activeSessionId": <string|null> }`；`id` 不存在返回 404。
- 「列表空则自动新建」（`SPEC-CHAT-TAB-004`）仍由前端决定（调用 API-003），后端只负责重选 active 指针。

### SPEC-CSP-API-006：`POST /desktop/chat/sessions/{id}/messages`（追加消息）

- 请求体为单条 `Message`，或 `{ "messages": [<Message>, ...] }`（支持一次追加 user + pending assistant）。
- 服务端为每条分配 `id`/`createdAt`，按序追加；对 `content` 执行截断（SPEC-CSP-DEC-005）；更新会话 `updatedAt`；执行单会话消息数裁剪（SPEC-CSP-API-009）；更新 index.json 的 `lastMessagePreview`/`messageCount`/`updatedAt`。
- 触发摘要异步再生成（SPEC-CSP-API-011）。
- 返回追加后的消息（含服务端 `id`/`createdAt`），HTTP 201；会话 `id` 不存在返回 404。

### SPEC-CSP-API-007：`PUT /desktop/chat/sessions/{id}/messages/{msgId}`（更新消息）

- 请求体部分字段：`{ "content"?, "status"?, "error"?, "suggestedTasks"? }`。
- 用于把 pending assistant 更新为 `sent`（写回回复与建议任务）或 `error`（写错误文案），以及「重试」时把 error 改回 pending。
- `status` 切换为 `pending` 或 `sent` 时必须清除旧 `error`；失败写入 `error` 时同时持久化格式化后的错误 `content`，避免重载后继续显示陈旧的“思考中”。
- 重试或重载恢复只能更新原 assistant 记录，不得追加新的 user/pending。允许对从 shard 重新加载到的遗留 pending 执行同一恢复流程；其前一条有效 user 消息的服务端 ID 是下一次 `/desktop/chat` 的 `userMessageId`。
- 更新会话 `updatedAt` 与 index.json 投影；触发摘要异步再生成（SPEC-CSP-API-011）；返回更新后的 `Message`；会话或 `msgId` 不存在返回 404。

### SPEC-CSP-API-008：`PUT /desktop/chat/active-session`（设置 active 指针）

- 请求体：`{ "activeSessionId": <string|null> }`。
- 持久化 active 指针（写 index.json）；非 `null` 且不指向已存在会话时返回 HTTP 400。
- 返回 `{ "activeSessionId": <string|null> }`。

### SPEC-CSP-API-009：服务端裁剪不变量

- **SPEC-CSP-API-009a**：**不限制会话数量**，不按会话数淘汰（SPEC-CSP-DEC-005）。
- **SPEC-CSP-API-009b**：任一会话追加消息后，仅保留最近 **200** 条消息（按追加顺序淘汰最旧）。
- **SPEC-CSP-API-009c**：写入任一消息 `content` 超 **20000** 字符时，保存前截断并追加 `...`。

### SPEC-CSP-API-010：原子写、分片隔离与降级

- **SPEC-CSP-API-010a**：每个分片文件与 index.json 各自以「临时文件 + 原子 rename」写入（沿用 `TaskStore.save`）；写失败返回 HTTP 500 且对应磁盘原文件保持不变。
- **SPEC-CSP-API-010b**：对某会话的写入**只触及该会话分片 + index.json**，不重写其它会话分片（分片隔离，SPEC-CSP-DEC-008）。
- **SPEC-CSP-API-010c**：读取损坏/不可解析的分片或索引时不抛未捕获异常致进程级故障：记录 warning 并按既定降级处理（索引可由分片重建，SPEC-CSP-MODEL-005），不静默删除原文件。

### SPEC-CSP-API-011：会话摘要生成（异步、可降级）

- **SPEC-CSP-API-011a**：当会话内容发生实质变化（assistant 回复落定 / 新消息追加）后，后端**异步、best-effort** 地（重新）生成该会话 `summary`（约一句话），写入分片与 index.json；可对再生成做节流/合并。
- **SPEC-CSP-API-011b**：摘要由 LLM 基于会话内容生成；**LLM 不可用 / 预算受限（`SPEC-BUDGET-*`）/ 调用失败**时回退**确定性兜底摘要**（会话内若干用户消息片段的拼接）。
- **SPEC-CSP-API-011c**：摘要生成**不得阻断** API-006/007 的响应；GET 返回**当前已有**摘要，允许滞后于最新消息。
- **SPEC-CSP-API-011d**：摘要的 LLM 输入仅为会话自身内容，**绝不**包含配置敏感值（SPEC-CSP-DEC-007/-010）；摘要调用计入 LLM 用量并受预算约束。

### SPEC-CSP-API-012：`POST /desktop/chat` 的会话路由、迁移与冲突

- 会话 tab 请求体必须包含顶层 `{ "message", "context", "sessionId", "userMessageId" }`。`sessionId` 必须为服务端生成的 lowercase hex32；`userMessageId` 必须为服务端生成的 lowercase hex12，并且在该 shard 中对应一条 user 消息。只有 legacy drawer/client 可同时省略两个 ID。
- ID 格式或 user turn 归属非法返回 HTTP 400；session 不存在或在取得 gate 后已被删除返回 404。
- 后端取得 application-wide Agent 生命周期 gate 后重新读取 shard，并以 shard 中 server-owned user content / `contextSnapshot` 为准。在 AgentState 不存在时，按 `SPEC-CSP-DEC-011` 导入当前 user 之前的旧服务端 transcript；之后以 `(userId=desktop, sessionId)` 调用 Agent。`contextSnapshot` 通过 RuntimeContext 临时进入本轮模型输入，不持久化进 user Msg，也不含 UI transcript/history。
- 若同一 `userMessageId` 已存在于 AgentState 且已有 terminal assistant，直接返回该回复，LLM 调用次数不增加；若 user turn 已存在但尚未完成，则从该 turn 继续，不追加重复 user。
- gate 已被其它聊天或删除占用时返回 HTTP 409 与 `{ "error": "..." }`。冲突请求不得追加 AgentState turn，也不得返回 HTTP 200 的占位 assistant 文案。

---

## 8. 前端集成契约

- **SPEC-CSP-FE-001**：`api.js` 新增对应 §7 各接口的方法（listSessions / getSession / createSession / updateSession / deleteSession / appendMessages / updateMessage / setActiveSession）。
- **SPEC-CSP-FE-002**：`chat.js` 的 `loadChatSessions()` 改为异步调用 `GET /desktop/chat/sessions`，把**索引元信息**填入 `state.chatSessions`（条目此时 `messages` 为空/未加载）与 `state.activeChatSessionId`；初始化须确保索引加载完成后再渲染列表。
- **SPEC-CSP-FE-003**：会话被激活/打开时，若其消息尚未加载，调用 `GET /desktop/chat/sessions/{id}` 拉取完整消息并缓存到该会话条目（懒加载正文）。
- **SPEC-CSP-FE-004**：原同步 `saveChatSessions()`「整块写 localStorage」移除；改为各交互点调用细粒度接口：
  - 新建会话 → `POST /sessions`（`SPEC-CHAT-TAB-003`）。
  - 切换 active → `PUT /active-session`（`SPEC-CHAT-TAB-004`）。
  - 删除会话 → `DELETE /sessions/{id}`（`SPEC-CHAT-TAB-004`）。
  - 重命名 / 首条消息回填标题 / 绑定上下文 → `PUT /sessions/{id}`（`SPEC-CHAT-TAB-003`、`-007`）。
  - 发送：先 `POST /sessions/{id}/messages` 追加 `user` + pending `assistant` 并采用服务端返回的两个消息 ID；再以顶层 `sessionId + userMessageId` 调用 `POST /desktop/chat`；最后只更新原 `pendingId` 为 `sent`/`error`（`SPEC-CHAT-TAB-005`、`-011`）。
- **SPEC-CSP-FE-005**：会话搜索（`SPEC-CHAT-TAB-004`）改为对 `state.chatSessions` 的索引字段过滤——匹配 `title` + `lastMessagePreview` + `summary`（SPEC-CSP-DEC-009），不依赖已加载的消息正文。
- **SPEC-CSP-FE-006**：前端不再读取 `localStorage` key `selfAnalyst.chatSessions.v1`，并主动删除该旧 key（SPEC-CSP-DEC-004）。
- **SPEC-CSP-FE-007**：写接口失败时按既有降级语义处理，不得因持久化失败丢弃用户已输入文本。`POST /desktop/chat` 的 409 按可重试错误处理，并把原 pending best-effort 更新为 error；不得把 409 body 渲染为成功 assistant。
- **SPEC-CSP-FE-008**：`POST /desktop/chat` 增加顶层 `sessionId + userMessageId`；会话 tab 两者必传，且均使用本轮消息追加响应中的服务端 ID。旧抽屉同时省略二者时保持 legacy fallback（`desktop-chat-tab.md` §10.1）。
- **SPEC-CSP-FE-009**：模型历史以 AgentState 为权威；UI transcript 不得再次作为 `context.history` 注入。
- **SPEC-CSP-FE-010**：失败重试必须复用原 `sessionId`、`userMessageId`、pending assistant ID 和 `contextSnapshot`，不得调用 API-006 追加新记录。若 AgentState 已有该 user turn 和完成回复，服务端返回既有回复，前端把原 pending 更新为 sent。
- **SPEC-CSP-FE-011**：从服务端懒加载正文后，任何遗留 pending 都必须呈现恢复/重试入口，或先规范化为可重试 error。恢复按原 transcript 顺序查找它前面的 user，依次执行“PUT 原 pending→pending → `POST /desktop/chat` 同 IDs → PUT 原 pending→sent/error”，不得永久停留在加载态。
- **SPEC-CSP-FE-012**：前端不提供 history toggle；右侧只保留当前状态、未来任务等业务上下文开关。

---

## 9. 非目标

- **SPEC-CSP-NON-001**：不改变 `会话` tab 的布局、视觉和键盘交互；业务上下文字段仍由 `desktop-chat-tab.md` 约束，但 history、ID、发送/重试顺序和 busy/delete 语义以本 spec 为准。
- **SPEC-CSP-NON-002**：不迁移既有 `localStorage` 历史会话（SPEC-CSP-DEC-004）。
- **SPEC-CSP-NON-003**：不实现云同步、跨设备同步、多端实时协同或账号体系。
- **SPEC-CSP-NON-004**：不提供消息级删除、会话归档；不建立倒排/全文检索索引——搜索为前端对会话**摘要**的本地过滤（SPEC-CSP-DEC-009），非逐字全文。
- **SPEC-CSP-NON-005**：除 `POST /desktop/chat` 的向后兼容 `sessionId + userMessageId` 扩展外，不改变 `/desktop/tasks`、`/desktop/config*` 等既有接口契约。
- **SPEC-CSP-NON-006**：不引入流式输出（与 `desktop-chat-tab.md` 一致）。
- **SPEC-CSP-NON-007**：摘要不保证逐字精确或实时一致；允许滞后于最新消息、允许在无 LLM/预算时为确定性兜底（SPEC-CSP-API-011）。

---

## 10. 测试规格

| 规格 ID | 测试 | 预期 |
|---------|------|------|
| SPEC-CSP-TST-001 | `GET /desktop/chat/sessions`，目录不存在 | 返回 `{ activeSessionId:null, sessions:[] }`，HTTP 200 |
| SPEC-CSP-TST-002 | `GET /sessions` 列表项 | 为 `SessionMeta`（含 `summary`/`lastMessagePreview`/`messageCount`），**不含** `messages` |
| SPEC-CSP-TST-003 | `POST /sessions` 创建后 `GET /sessions/{id}` | 服务端分配了 `id`/`createdAt`/`updatedAt`，落盘为独立分片文件，新会话为 `activeSessionId` |
| SPEC-CSP-TST-004 | `POST /sessions/{id}/messages` 追加 user+pending 后 `GET /sessions/{id}` | 两条消息按序存在并各有服务端 `id` |
| SPEC-CSP-TST-005 | `PUT /sessions/{id}/messages/{msgId}` 把 pending 改为 sent + suggestedTasks | 消息 `status=sent`、内容/建议已更新，会话 `updatedAt` 前移 |
| SPEC-CSP-TST-006 | `PUT /sessions/{id}` 改标题 | 标题更新，`messages` 不受影响，index.json 投影同步 |
| SPEC-CSP-TST-007 | `DELETE /sessions/{id}`，删除的是 active | 返回 `deleted:true` 且 `activeSessionId` 重选为剩余最新者（无则 null），分片文件被移除 |
| SPEC-CSP-TST-008 | 创建大量会话（如 200 个） | **无淘汰**，全部保留且可读（SPEC-CSP-API-009a / DEC-005） |
| SPEC-CSP-TST-009 | 向会话 A 追加消息 | 仅会话 A 分片与 index.json 被改写，会话 B 分片文件内容/时间戳不变（分片隔离，SPEC-CSP-API-010b） |
| SPEC-CSP-TST-010 | 单会话追加超过 200 条消息 | 仅保留最近 200 条（SPEC-CSP-API-009b） |
| SPEC-CSP-TST-011 | 追加 `content` 超 20000 字符的消息 | 落盘内容被截断且以 `...` 结尾（SPEC-CSP-API-009c） |
| SPEC-CSP-TST-012 | `GET/PUT/DELETE /sessions/{id}` 未知 id | 返回 HTTP 404 |
| SPEC-CSP-TST-013 | `PUT /active-session` 指向不存在会话 | 返回 HTTP 400，磁盘 active 指针不变 |
| SPEC-CSP-TST-014 | 删除/损坏 index.json 后 `GET /sessions` | 从分片重建索引、会话正文不丢（SPEC-CSP-MODEL-005 / API-010c） |
| SPEC-CSP-TST-015 | 追加消息后摘要再生成（LLM 不可用） | 会话 `summary` 为确定性兜底文本（非空），收发未被阻断（SPEC-CSP-API-011b/c） |
| SPEC-CSP-TST-016 | 前端按摘要搜索（手动/UI） | 输入命中某会话 `summary` 的词，列表过滤出该会话（SPEC-CSP-FE-005） |
| SPEC-CSP-TST-017 | 前端发送消息（手动/UI） | Network 出现 `POST .../messages` → 带服务端 `sessionId + userMessageId` 的 `POST /desktop/chat` → `PUT .../messages/{pendingId}`，刷新后消息仍在 |
| SPEC-CSP-TST-018 | 升级后首次加载（手动/UI） | 不读取旧 `localStorage` key，且该旧 key 被删除（SPEC-CSP-FE-006） |
| SPEC-CSP-TST-019 | A/B 两个 session 交替对话并重启 Agent | B 请求不含 A 历史；重启后 A 从自身 AgentState 恢复历史；请求不含 `context.history` |
| SPEC-CSP-TST-020 | 删除会话后以原 ID 调用读取/聊天 | 返回 404；该 ID 的 shard、AgentState 与 cache 均已清除 |
| SPEC-CSP-TST-021 | `.`, `..`, 正反斜杠、超长或 Unicode session ID | 返回/抛出非法 ID，且 chat-sessions 目录外文件不变 |
| SPEC-CSP-TST-022 | 已有 server shard、无 AgentState，追加当前 user 后首次聊天 | 只把当前 user 之前的有效 user/sent assistant seed 到 AgentState；system/pending/error 被排除；后续请求不重复迁移 |
| SPEC-CSP-TST-023 | Agent 已完成回复但原 pending 尚未更新，刷新后恢复 | UI 复用原 session/user/pending IDs；模型调用次数不增加；原 pending 更新为 sent，无重复 turn |
| SPEC-CSP-TST-024 | 刷新加载到未完成 pending | pending 显示恢复/重试入口；按 PUT pending → POST chat → PUT sent/error 有序执行，不追加消息 |
| SPEC-CSP-TST-025 | 另一聊天占用 Agent gate 时发送 | 返回 HTTP 409 `error`；AgentState 不追加被拒绝 turn，UI 将原 pending 置为可重试 error |
| SPEC-CSP-TST-026 | Agent gate 忙时删除会话 | 返回 409，shard/index/AgentState/cache 全部保持；稍后重试成功时在同一 gate 内全部清除 |
| SPEC-CSP-TST-027 | LLM 未配置、agent 为 null 时删除有遗留 AgentState 的会话 | 直接打开状态存储清除 AgentState，再删除 shard/index，不遗留隐藏模型上下文 |
| SPEC-CSP-TST-028 | 低阈值触发压缩并重启 | AgentState 保存 rolling summary + recent context；重启后的模型输入恢复两者，旧 raw prefix 不再反序列化/重写 |
| SPEC-CSP-TST-029 | compaction summary 调用失败 | 原 AgentState 文件字节不变；不得持久化 `(Summarization failed: ...)` 覆盖旧前缀 |

---

## 规格追溯矩阵

| 规格 ID | 对应文件/组件 | 验证方式 |
|---------|--------------|---------|
| SPEC-CSP-DEC-001 | 本 spec（取代说明）、`desktop-chat-tab.md` | 代码审查 |
| SPEC-CSP-DEC-002..012 | 本 spec（设计决策） | 代码审查 |
| SPEC-CSP-GOAL-001..008 | 全特性 | 验收测试 |
| SPEC-CSP-MODEL-001..005 | `ChatSessionStore.java`（模型、id 安全、索引重建） | 单元测试 |
| SPEC-CSP-API-001..002 | `DesktopChatSessionController.java`、`DesktopServer.java`、`ChatSessionStore.java` | 单元测试 |
| SPEC-CSP-API-003..005 | `DesktopChatSessionController.java`、`ChatSessionStore.java` | 单元测试 |
| SPEC-CSP-API-006..007 | `DesktopChatSessionController.java`、`ChatSessionStore.java` | 单元测试 |
| SPEC-CSP-API-008 | `DesktopChatSessionController.java`、`DesktopServer.java`、`ChatSessionStore.java` | 单元测试 |
| SPEC-CSP-API-009 | `ChatSessionStore.java`（裁剪不变量） | 单元测试 |
| SPEC-CSP-API-010 | `ChatSessionStore.java`（原子写/分片隔离/降级） | 单元测试、代码审查 |
| SPEC-CSP-API-011 | `ChatSummaryService.java`、`DesktopChatSessionController.java`（异步再生成）、`ChatSessionStore.java`（writeSummary） | 单元测试（`ChatSummaryServiceTest`）、代码审查 |
| SPEC-CSP-API-012 | `DesktopAgentController.java`、`SelfAnalystAgent.java`、`ChatSessionStore.java` | 单元/集成测试、代码审查 |
| SPEC-CSP-FE-001..012 | `desktop-ui/api.js`、`chat.js`、`state.js`、`init.js`、`events.js` | 手动/验收测试、`scripts/check-desktop-chat-session-store.ps1`、JS 测试、代码审查 |
| SPEC-CSP-NON-001..007 | 全特性 | 代码审查 |
| SPEC-CSP-TST-001..015 | `ChatSessionStoreTest.java`、`ChatSummaryServiceTest.java` | 单元测试 |
| SPEC-CSP-TST-016..018 | `scripts/check-desktop-chat-session-store.ps1` + 运行桌面端 | 手动/验收测试 |
| SPEC-CSP-TST-019..027 | Agent session-state 测试、controller 测试、JS routing/recovery 测试 | 单元/集成/手动测试 |
