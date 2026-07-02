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

用户诉求：**把会话改为后端持久化**，落在与 `tasks.json` 同级的 `{memoryDir}` 数据目录；采用 REST CRUD 粒度的接口；**会话数量不设上限**；为避免"单文件随会话累积而整文件重写变慢"，存储采用**按会话分片 + 轻量索引**。本特性只改变**存储后端、读写通道与会话搜索的数据来源**，不改变 `会话` tab 的布局、上下文构建与发送语义（那些仍由 `desktop-chat-tab.md` 约束）。

后端持久化范式已在 `TaskStore`（`tasks.json`，临时文件 + 原子 rename）与 `UserConfigStore` / `ConfigHistoryStore` 中确立；会话摘要的"LLM 生成 + 确定性兜底 + 异步不阻断"范式已在配置版本历史（`SPEC-CFGUI-VER-DEC-002`）中确立。本特性沿用这两套范式。

---

## 3. 与既有 spec 的关系（取代说明）

- **SPEC-CSP-DEC-001**：本特性**取代** `desktop-chat-tab.md` §9「本地持久化」与其非目标「不要求新增 Java 后端会话存储 API」「会话记录使用 WebView/localStorage 本地保存」。这些条款标注为**已被 `SPEC-CSP-*` 取代**；`desktop-chat-tab.md` 其余交互/UI/上下文/发送行为（`SPEC-CHAT-TAB-001..012`）**继续有效**，但下列两点被本 spec 修订：
  - 仍然成立的行为约束（仅存储后端变化）：何时持久化（创建/切换 active/发送收发/删除/重命名后）、会话与消息的数据字段、单会话 200 消息上限、单条消息 20000 字符上限、不得把 API Key 或配置敏感值写入会话存储。
  - **修订一**：`SPEC-CHAT-TAB` §9「最多保留 50 个会话」**被取消**——会话数量不再设上限（`SPEC-CSP-DEC-005`）。
  - **修订二**：`SPEC-CHAT-TAB-004`「搜索框按 title 和**消息内容**过滤」的语义改为「按 title、最后一条消息预览、**会话摘要**过滤」（`SPEC-CSP-DEC-009`）——不再对全部历史消息正文做前端全文匹配。
  - `SPEC-CHAT-TAB-004`/`-005` 等条文中「保存到 localStorage」一句的语义改为「通过本 spec 的 REST 接口持久化到后端」。

---

## 4. 设计结论（决策与取舍）

- **SPEC-CSP-DEC-002**：会话与消息的**唯一事实来源**为后端 `{memoryDir}/chat-sessions/` 目录下的文件，前端不再以 `localStorage` 为主数据源。前端运行期在 `state.chatSessions` 内存缓存以驱动渲染，但所有写操作必须经 REST 接口落盘，刷新后从后端重新加载。
  - *取舍*：与 `tasks.json` 同目录范式，数据可被后端复用、随用户数据目录迁移；放弃纯前端零依赖（本就是 localhost 单机后端，前后端共生）。

- **SPEC-CSP-DEC-003**：写操作采用 **REST CRUD 粒度**（按会话、按消息的细粒度增删改），**不**采用「前端提交整份文档、后端整块覆盖」的 blob PUT。
  - *取舍*：细粒度接口避免并发整块回写的丢更新，单次 payload 小，与 `TaskStore` CRUD 风格一致。

- **SPEC-CSP-DEC-004**：**不迁移**既有 `localStorage`（`selfAnalyst.chatSessions.v1`）历史会话，直接切换。前端不再读取该 key，并**应**在切换后主动删除该旧 key。
  - *取舍*：用户明确选择不迁移；会话历史价值低、易重建，省去一次性迁移代码。

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

  - **分片文件**是单个会话的**事实来源**；**index.json** 是从各分片派生的**投影/缓存**（用于列表与搜索），可在损坏/缺失时由分片重建。
  - 写一条消息只重写**该会话的分片**（≤200 条，有界）+ 更新 **index.json 中对应一行**。写成本与历史会话总数无关。
  - *取舍*：index.json 仍随会话数线性增长，但只含**元信息**（无消息正文），量级约为"会话数 × 数百字节"，远小于全量；其重写发生在会话元信息变化（新建/删除/标题或摘要/`updatedAt` 变更）时。这是为"会话数不限"换取的可接受成本。

- **SPEC-CSP-DEC-009**（会话搜索数据来源）：会话搜索为**前端对 index.json 各会话条目的本地过滤**，匹配字段为 **title + 最后一条消息预览 + 会话摘要**（`summary`）。不把全部历史消息正文加载到前端、也不在每次查询时扫描分片文件。
  - *取舍*：会话数不限后，"前端加载全部消息正文做全文搜索"不可行、"每次查询扫盘"会随会话增多变慢；改为搜索一份**压缩进索引的摘要**，兼顾零扫盘与"能搜到聊过的内容"。代价：搜索召回受摘要质量限制，非逐字全文。

- **SPEC-CSP-DEC-010**（会话摘要生成）：每个会话维护一段**简短摘要**（约一句话），作为搜索的"正文代理"并可用于展示提示。摘要由 **LLM 基于会话内容生成**，**LLM 不可用 / 预算受限（`SPEC-BUDGET-*`）/ 失败**时回退到**确定性兜底摘要**（由会话内若干用户消息片段拼接得到）。生成是**异步、best-effort、不得阻断**消息收发；后端可对再生成做节流/合并。
  - *隐私*：摘要的 LLM 输入是**会话自身内容**——该内容在聊天时本就发送给同一 LLM 端点，故不引入新泄露面；但摘要输入**绝不**包含配置敏感值（与 `SPEC-CSP-DEC-007` 一致）。摘要生成计入 LLM 用量并受预算约束。

---

## 5. 目标

- **SPEC-CSP-GOAL-001**：会话与消息持久化到 `{memoryDir}/chat-sessions/`，刷新页面、重启后端后会话历史仍在。
- **SPEC-CSP-GOAL-002**：提供按会话、按消息的 REST CRUD 接口，覆盖列表（索引）、单会话读取、创建会话、更新会话元信息、删除会话、追加消息、更新消息（pending→sent/error）、设置 active 会话。
- **SPEC-CSP-GOAL-003**：`会话` tab 现有交互与外观（`desktop-chat-tab.md`）不变，仅存储通道、会话数上限与搜索数据来源按本 spec 调整。
- **SPEC-CSP-GOAL-004**：写入须原子落盘，任一接口失败须返回可读错误，且不破坏已落盘文件。
- **SPEC-CSP-GOAL-005**：会话数不设上限；单会话消息数与单条消息长度上限由后端强制。
- **SPEC-CSP-GOAL-006**：每个会话维护可搜索的摘要，支撑"按聊过的内容搜会话"，且其生成不阻断收发、可在无 LLM/预算时降级。

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

- 删除该会话分片文件并从 index.json 移除。
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
  - 发送：先 `POST /sessions/{id}/messages` 追加 `user` + pending `assistant`，调用既有 `POST /desktop/chat` 取回复，再 `PUT .../messages/{pendingId}` 更新为 `sent`/`error`（`SPEC-CHAT-TAB-005`、`-011`）。
- **SPEC-CSP-FE-005**：会话搜索（`SPEC-CHAT-TAB-004`）改为对 `state.chatSessions` 的索引字段过滤——匹配 `title` + `lastMessagePreview` + `summary`（SPEC-CSP-DEC-009），不依赖已加载的消息正文。
- **SPEC-CSP-FE-006**：前端不再读取 `localStorage` key `selfAnalyst.chatSessions.v1`，并主动删除该旧 key（SPEC-CSP-DEC-004）。
- **SPEC-CSP-FE-007**：写接口失败时按既有降级语义处理（如发送失败把 pending 标为 error 并提供重试），不得因持久化失败丢弃用户已输入文本。
- **SPEC-CSP-FE-008**：`POST /desktop/chat`（LLM 调用）契约不变（`desktop-chat-tab.md` §10.1），与会话存储接口相互独立。

---

## 9. 非目标

- **SPEC-CSP-NON-001**：不改变 `会话` tab 的布局、视觉、上下文构建、键盘交互与发送语义（仍由 `desktop-chat-tab.md` 约束）。
- **SPEC-CSP-NON-002**：不迁移既有 `localStorage` 历史会话（SPEC-CSP-DEC-004）。
- **SPEC-CSP-NON-003**：不实现云同步、跨设备同步、多端实时协同或账号体系。
- **SPEC-CSP-NON-004**：不提供消息级删除、会话归档；不建立倒排/全文检索索引——搜索为前端对会话**摘要**的本地过滤（SPEC-CSP-DEC-009），非逐字全文。
- **SPEC-CSP-NON-005**：不改变 `POST /desktop/chat`、`/desktop/tasks`、`/desktop/config*` 等既有接口契约。
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
| SPEC-CSP-TST-017 | 前端发送消息（手动/UI） | Network 出现 `POST .../messages` → `POST /desktop/chat` → `PUT .../messages/{id}`，刷新后消息仍在 |
| SPEC-CSP-TST-018 | 升级后首次加载（手动/UI） | 不读取旧 `localStorage` key，且该旧 key 被删除（SPEC-CSP-FE-006） |

---

## 规格追溯矩阵

| 规格 ID | 对应文件/组件 | 验证方式 |
|---------|--------------|---------|
| SPEC-CSP-DEC-001 | 本 spec（取代说明）、`desktop-chat-tab.md` | 代码审查 |
| SPEC-CSP-DEC-002..010 | 本 spec（设计决策） | 代码审查 |
| SPEC-CSP-GOAL-001..006 | 全特性 | 验收测试 |
| SPEC-CSP-MODEL-001..005 | `ChatSessionStore.java`（模型、id 安全、索引重建） | 单元测试 |
| SPEC-CSP-API-001..002 | `DesktopChatSessionController.java`、`DesktopServer.java`、`ChatSessionStore.java` | 单元测试 |
| SPEC-CSP-API-003..005 | `DesktopChatSessionController.java`、`ChatSessionStore.java` | 单元测试 |
| SPEC-CSP-API-006..007 | `DesktopChatSessionController.java`、`ChatSessionStore.java` | 单元测试 |
| SPEC-CSP-API-008 | `DesktopChatSessionController.java`、`DesktopServer.java`、`ChatSessionStore.java` | 单元测试 |
| SPEC-CSP-API-009 | `ChatSessionStore.java`（裁剪不变量） | 单元测试 |
| SPEC-CSP-API-010 | `ChatSessionStore.java`（原子写/分片隔离/降级） | 单元测试、代码审查 |
| SPEC-CSP-API-011 | `ChatSummaryService.java`、`DesktopChatSessionController.java`（异步再生成）、`ChatSessionStore.java`（writeSummary） | 单元测试（`ChatSummaryServiceTest`）、代码审查 |
| SPEC-CSP-FE-001..008 | `desktop-ui/api.js`、`chat.js`、`state.js`、`init.js`、`events.js` | 手动/验收测试、`scripts/check-desktop-chat-session-store.ps1`、代码审查 |
| SPEC-CSP-NON-001..007 | 全特性 | 代码审查 |
| SPEC-CSP-TST-001..015 | `ChatSessionStoreTest.java`、`ChatSummaryServiceTest.java` | 单元测试 |
| SPEC-CSP-TST-016..018 | `scripts/check-desktop-chat-session-store.ps1` + 运行桌面端 | 手动/验收测试 |
