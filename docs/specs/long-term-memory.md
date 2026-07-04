# SelfAnalyst 长期记忆 SDD 规格说明书

> Specification-Driven Development spec. 本文档定义 SelfAnalyst 从会话中自动提炼长期记忆，并允许用户通过会话设置与记忆管理界面控制记忆的行为契约。实现必须可追溯至本文档中的规格 ID。

---

## 1. 文档元信息

| 属性 | 值 |
|------|-----|
| 功能名称 | 长期记忆：从会话自动提炼 + 用户确认/管理 |
| 文档状态 | Ready for implementation |
| 日期 | 2026-07-04 |
| 目标平台 | SelfAnalyst Tauri 桌面端内嵌 WebView，Windows 优先 |
| 规格前缀 | `SPEC-LTM-*`（Long-Term Memory） |
| 主要后端入口 | `self-analyst-app/src/main/java/com/selfanalyst/memory/`、`self-analyst-app/src/main/java/com/selfanalyst/desktop/controller/` |
| 主要前端入口 | `self-analyst-app/src/main/resources/desktop-ui/` |
| 存储位置 | `{memoryDir}/memory.json` |

---

## 2. 背景与当前状态

当前 SelfAnalyst 已有 `MemoryStore` 与 `GrowthProfile`，将 `memory.json` 作为用户长期档案。既有模型包含 `goals`、`patterns`、`logs`，并在 Agent 系统提示词中作为上下文使用。

当前限制：

- `GrowthProfile` 主要是静态档案；普通聊天不会自动把有价值的信息写回长期记忆。
- `memory.json` 可以人工编辑，但桌面端没有用户友好的记忆候选确认、手动添加、停用、删除入口。
- 当前 Agent 的系统提示词在初始化时构建，长期记忆变更后不应要求用户重启应用才能生效。
- 会话后端持久化（`SPEC-CSP-*`）已经提供消息落盘与会话摘要，但尚未把会话内容转化为长期用户画像/目标/偏好。

本特性把“会话历史”与“长期记忆”连接起来：聊天成功后异步提炼可长期复用的记忆；低风险、用户明确陈述的内容可自动保存；涉及推断、敏感、低置信度或行为判断的内容必须由用户确认。

---

## 3. 与既有 spec 的关系

- **SPEC-LTM-REL-001**：本 spec **扩展** `core.md` 中的 `SPEC-MDL-*` 与 `SPEC-MEM-*`。`goals`、`patterns`、`logs` 的既有 JSON 字段继续兼容；新增长期记忆项不得破坏旧 `memory.json` 的反序列化。
- **SPEC-LTM-REL-002**：本 spec **遵守** `SPEC-ARCH-003`：`memory.json` 的文件系统读写仍只能通过 `MemoryStore` 完成；其它模块不得直接读写该文件。
- **SPEC-LTM-REL-003**：本 spec **扩展** `chat-session-store.md` 的会话模型与接口：会话可拥有记忆策略，assistant 回复落定后可触发长期记忆提炼。
- **SPEC-LTM-REL-004**：本 spec **不取代** `desktop-chat-tab.md` 的聊天布局、上下文构建、任务建议与发送语义；仅新增长期记忆相关界面与后端通道。
- **SPEC-LTM-REL-005**：长期记忆提炼调用 LLM 时必须计入 `SPEC-BUDGET-*` 预算；预算阻断或 LLM 不可用时，提炼降级为 no-op，不影响聊天。

---

## 4. 设计结论（决策与取舍）

- **SPEC-LTM-DEC-001**：长期记忆的唯一事实来源是 `{memoryDir}/memory.json`。不新增 SQLite、向量库或单独的 `memories.json` 作为第一版事实来源。
  - 取舍：复用现有 `MemoryStore`，便于迁移与备份；暂不解决大规模语义检索问题。

- **SPEC-LTM-DEC-002**：采用分级入库策略（用户选择的 C 策略）：
  - 低风险、用户明确陈述、可长期复用的事实可自动保存。
  - 行为模式、敏感信息、模型推断、低置信度内容必须先进入待确认。
  - 明显临时、凭据、密钥、一次性原文隐私片段不得保存。

- **SPEC-LTM-DEC-003**：自动提炼异步、best-effort、不得阻断聊天。聊天 API 与会话消息写入成功后即可返回；提炼失败只记录状态/日志，不改变用户可见的聊天结果。

- **SPEC-LTM-DEC-004**：每个会话可设置记忆策略：`smart`、`confirm_all`、`off`。默认 `smart`。
  - `smart`：按 SPEC-LTM-DEC-002 分级。
  - `confirm_all`：所有候选都进入待确认，不自动 active。
  - `off`：该会话不触发自动提炼；用户仍可手动添加记忆。

- **SPEC-LTM-DEC-005**：下一轮聊天必须使用最新的 active 记忆。记忆增删改或批准后，不要求用户重启应用才能进入 Agent 上下文。

- **SPEC-LTM-DEC-006**：长期记忆必须可由用户管理。用户可以查看、搜索、手动添加、编辑、批准、拒绝、停用、删除记忆。

- **SPEC-LTM-DEC-007**：长期记忆输入的 LLM 提炼范围必须有限。提炼请求只使用当前会话最近一组或有限窗口消息、当前 active/pending 记忆摘要、必要的来源 ID；不得把完整历史会话库全量发送给 LLM。

- **SPEC-LTM-DEC-008**：第一版不做向量检索。进入聊天上下文的长期记忆由 `GrowthProfile.buildContextSummary()` 输出一份短摘要；去重、状态过滤、数量上限由确定性规则完成。

---

## 5. 目标

- **SPEC-LTM-GOAL-001**：聊天成功后能从用户/助手消息中提炼长期可用信息。
- **SPEC-LTM-GOAL-002**：用户明确陈述的低风险偏好、目标、长期项目事实可自动保存。
- **SPEC-LTM-GOAL-003**：敏感、推断、行为模式、低置信度内容必须先由用户确认。
- **SPEC-LTM-GOAL-004**：用户可通过会话设置关闭自动提炼或要求全部确认。
- **SPEC-LTM-GOAL-005**：用户可在桌面 UI 中管理长期记忆。
- **SPEC-LTM-GOAL-006**：active 记忆会被稳定注入后续 Agent 对话与摘要上下文。
- **SPEC-LTM-GOAL-007**：记忆提炼失败、LLM 不可用、JSON 格式异常、预算阻断均不得影响聊天主流程。

---

## 6. 非目标

- **SPEC-LTM-NON-001**：第一版不实现云同步、账号体系、跨设备合并。
- **SPEC-LTM-NON-002**：第一版不实现向量库、相似度检索或 embedding 驱动的记忆召回。
- **SPEC-LTM-NON-003**：不把 ActivityWatch 原始事件自动写入长期记忆；行为推断只能作为待确认候选。
- **SPEC-LTM-NON-004**：不自动保存密码、token、API key、私钥、验证码、银行卡号、身份证号等凭据或高敏标识。
- **SPEC-LTM-NON-005**：不把所有聊天历史原文复制进长期记忆；长期记忆必须是短句/结构化摘要。
- **SPEC-LTM-NON-006**：不要求迁移旧 `goals`、`patterns`、`logs` 到新 `MemoryItem`；旧字段继续读取与展示。

---

## 7. 数据模型

### 7.1 `GrowthProfile` 扩展

`GrowthProfile` 保留既有字段：

- `goals`
- `patterns`
- `logs`

新增：

```java
List<MemoryItem> memories
```

- **SPEC-LTM-MDL-001**：`memories` 缺失时视为空列表，旧版 `memory.json` 必须可读取。
- **SPEC-LTM-MDL-002**：写回 `memory.json` 时保留旧字段；新增字段使用 pretty JSON 与 ISO 时间格式，沿用 `SPEC-MDL-005`。

### 7.2 `MemoryItem`

```java
record MemoryItem(
    String id,
    String type,
    String content,
    String evidence,
    int confidence,
    String status,
    boolean sensitive,
    String approvalPolicy,
    String source,
    String sourceSessionId,
    List<String> sourceMessageIds,
    Instant createdAt,
    Instant updatedAt
)
```

| 字段 | 允许值 / 类型 | 说明 |
|------|---------------|------|
| `id` | string | 服务端生成，唯一，建议 UUID 前 12 位或等价短 ID |
| `type` | `goal` \| `preference` \| `project` \| `pattern` \| `fact` \| `note` | 记忆类别 |
| `content` | string | 可直接给 Agent 使用的短句，不保存大段原文 |
| `evidence` | string? | 简短来源说明，例如“来自 2026-07-04 会话” |
| `confidence` | 1..10 | 置信度 |
| `status` | `active` \| `pending` \| `disabled` \| `rejected` | 生命周期状态 |
| `sensitive` | boolean | 是否涉及敏感或推断性内容 |
| `approvalPolicy` | `auto` \| `confirm` | 入库策略 |
| `source` | `chat_auto` \| `chat_manual` \| `ui_manual` \| `legacy` | 来源 |
| `sourceSessionId` | string? | 来源会话 |
| `sourceMessageIds` | string[] | 来源消息 ID，可为空 |
| `createdAt` / `updatedAt` | string(ISO-8601) | 服务端写入 |

- **SPEC-LTM-MDL-003**：`content` 必须为短句，保存前去除首尾空白；空内容不得写入。
- **SPEC-LTM-MDL-004**：`confidence` 必须归一到 1..10；超出范围时服务端夹取或拒绝。
- **SPEC-LTM-MDL-005**：`status != active` 的记忆不得进入 Agent 上下文。
- **SPEC-LTM-MDL-006**：`rejected` 记忆可保留用于避免重复推荐，但不得进入上下文；用户执行删除时必须物理移除。
- **SPEC-LTM-MDL-007**：同一 `content` 的规范化文本已存在 active/pending 项时，不得创建重复记忆；应合并来源或忽略重复候选。

### 7.3 会话记忆策略

会话模型扩展：

```json
{
  "memoryPolicy": "smart"
}
```

- **SPEC-LTM-MDL-010**：`memoryPolicy` 允许值为 `smart`、`confirm_all`、`off`；缺失时等价于 `smart`。
- **SPEC-LTM-MDL-011**：`SessionMeta` 应包含 `memoryPolicy`，使前端会话列表或会话面板无需加载完整消息即可显示/修改当前策略。

---

## 8. 提炼与入库规则

### SPEC-LTM-EXTR-001：触发时机

当 assistant 消息从 `pending` 更新为 `sent`，且所属会话 `memoryPolicy != off` 时，后端应异步触发一次长期记忆提炼。

### SPEC-LTM-EXTR-002：输入范围

提炼输入只允许包含：

- 当前会话 ID、标题、记忆策略。
- 最近一轮 user/assistant 消息，或有界的最近消息窗口。
- 当前 active/pending 记忆的短摘要，用于去重与避免重复提议。
- 相关消息 ID。

不得包含：

- 配置密钥、API key、token。
- 全量历史会话库。
- ActivityWatch 原始事件流。

### SPEC-LTM-EXTR-003：输出格式

LLM 提炼结果必须被解析为结构化候选列表。每个候选至少包含：

```json
{
  "type": "preference",
  "content": "用户偏好使用中文交流。",
  "evidence": "用户明确要求后续用中文。",
  "confidence": 9,
  "sensitive": false,
  "approvalPolicy": "auto"
}
```

无法解析、字段缺失或 JSON 格式错误时，本次提炼结果整体丢弃或只保留可验证项；不得抛出到聊天接口。

### SPEC-LTM-EXTR-004：自动保存条件

候选只有同时满足以下条件才可自动变为 `active`：

- 用户明确陈述，而非模型推断。
- 非敏感。
- 非凭据/密钥/高敏标识。
- 对未来多次对话有明显帮助。
- `confidence >= 8`。
- 未与现有 active/pending 记忆重复。

典型自动保存类型：

- 明确偏好：语言、输出风格、工作方式偏好。
- 明确目标：用户说“我想/我要/我的目标是...”。
- 长期项目事实：用户持续工作的项目、角色、约束。

### SPEC-LTM-EXTR-005：必须确认条件

候选满足任一条件时必须进入 `pending`：

- 来自模型推断，而非用户明确陈述。
- 涉及行为模式、性格、心理、健康、财务、法律、关系、身份等敏感或可能伤害用户的判断。
- 来自 ActivityWatch/行为数据的归纳。
- `confidence < 8`。
- 会话策略为 `confirm_all`。

### SPEC-LTM-EXTR-006：禁止保存条件

满足任一条件时不得写入 active 或 pending：

- 密码、token、API key、私钥、验证码。
- 一次性临时任务、短期上下文、仅本轮有用的信息。
- 大段原文、聊天全文、未经概括的隐私文本。
- 用户明确表示“不要记住”的内容。

### SPEC-LTM-EXTR-007：用户指令优先

当用户在会话中明确要求“记住 X”，若 X 不触发 SPEC-LTM-EXTR-006，则可按 `chat_manual` 或 `chat_auto` 来源保存；若 X 敏感或低置信，仍进入 `pending`。

当用户明确要求“忘记/不要记住 X”，系统必须停用或删除匹配记忆，并不得在同一会话中再次自动创建等价记忆。

### SPEC-LTM-EXTR-008：幂等与去重

同一 assistant 回复多次重试或同一消息被重复更新时，不得产生重复记忆。实现必须以来源消息 ID、规范化内容或等价 fingerprint 保证幂等。

### SPEC-LTM-EXTR-009：预算与语言

提炼调用计入 LLM 用量预算；预算阻断时本次提炼 no-op。提炼提示词与候选内容语言应跟随有效语言（`SPEC-I18N-*`），但不得翻译用户原意。

---

## 9. Agent 上下文契约

- **SPEC-LTM-PROMPT-001**：每次 `chat()` 调用时，Agent 使用的长期记忆摘要必须来自当前 `MemoryStore.profile()` 的最新状态。
- **SPEC-LTM-PROMPT-002**：长期记忆摘要只包含 `status=active` 的 `MemoryItem`，以及 legacy `goals/patterns/logs` 中仍有效的内容。
- **SPEC-LTM-PROMPT-003**：pending/rejected/disabled 记忆不得进入 Agent system prompt、聊天上下文或摘要增强提示。
- **SPEC-LTM-PROMPT-004**：长期记忆摘要应按类别分组，并限制长度；超出上限时优先保留目标、偏好、长期项目，再保留高置信事实与模式。
- **SPEC-LTM-PROMPT-005**：记忆变更后下一轮聊天即可生效，不得要求重启后端或重建 `AppSession`。

---

## 10. 后端接口契约

接口注册于共享 Javalin 实例。错误统一返回 `{ "error": <string> }` 与对应 HTTP 状态码。

### SPEC-LTM-API-001：`GET /desktop/memory`

返回长期记忆列表：

```json
{
  "memories": [ ... ],
  "legacy": {
    "goals": [ ... ],
    "patterns": [ ... ],
    "logs": [ ... ]
  }
}
```

可选 query：

- `status`
- `type`
- `sourceSessionId`
- `q`

未传 query 时返回所有非删除记忆，按 `updatedAt` 降序。

### SPEC-LTM-API-002：`POST /desktop/memory`

手动添加记忆。请求体：

```json
{
  "type": "goal",
  "content": "...",
  "evidence": "...",
  "sourceSessionId": "...",
  "status": "active"
}
```

服务端生成 `id`、时间戳、默认 `confidence=10`、`source=ui_manual` 或 `chat_manual`。敏感内容可要求二次确认或保存为 `pending`。

### SPEC-LTM-API-003：`PUT /desktop/memory/{id}`

更新单条记忆。允许修改：

- `type`
- `content`
- `evidence`
- `confidence`
- `status`
- `sensitive`

用于批准（`pending -> active`）、拒绝（`pending -> rejected`）、停用（`active -> disabled`）、重新启用（`disabled -> active`）、编辑后批准。

### SPEC-LTM-API-004：`DELETE /desktop/memory/{id}`

物理删除单条记忆。删除后不得再进入上下文，也不应作为去重依据。

### SPEC-LTM-API-005：`PUT /desktop/chat/sessions/{id}/memory-policy`

设置会话记忆策略。请求体：

```json
{ "memoryPolicy": "confirm_all" }
```

返回更新后的会话元信息或完整会话。非法策略返回 HTTP 400。

### SPEC-LTM-API-006：`POST /desktop/chat/sessions/{id}/memory`

从当前会话手动添加记忆。请求体与 API-002 类似，但服务端自动填充 `sourceSessionId`，`source=chat_manual`。

### SPEC-LTM-API-007：写入原子性

所有记忆新增、更新、删除必须经 `MemoryStore.save()` 持久化。写失败返回 HTTP 500，内存状态应避免与磁盘状态长期不一致。

### SPEC-LTM-API-008：并发

同一进程内对 `GrowthProfile.memories` 的修改必须串行化，避免同时批准/提炼/删除导致丢更新。

### SPEC-LTM-API-009：错误隔离

提炼、去重、保存候选失败不得让 `/desktop/chat`、会话消息 API 或桌面 summary API 返回失败，除非用户正在直接调用 `/desktop/memory*` 写接口。

---

## 11. 前端集成契约

### SPEC-LTM-FE-001：API client

`desktop-ui/api.js` 新增对应 §10 的 API 方法：list/create/update/delete memory，set session memory policy，create memory from session。

### SPEC-LTM-FE-002：应用状态

`state` 新增：

```js
memoryItems: [],
memoryFilter: "",
memoryLoading: false,
pendingMemoryCount: 0
```

会话条目新增/读取 `memoryPolicy`。

### SPEC-LTM-FE-003：会话右侧面板

`会话` tab 右侧上下文面板新增“长期记忆”区，必须展示：

- 当前会话记忆策略：智能 / 每次确认 / 关闭。
- 待确认候选数量。
- 当前会话相关记忆摘要。
- 手动添加记忆入口。

### SPEC-LTM-FE-004：候选确认

待确认候选必须支持：

- 批准。
- 编辑后批准。
- 拒绝。
- 停用已批准记忆。

这些操作必须调用后端 API，不得只改前端状态。

### SPEC-LTM-FE-005：全局记忆管理

配置弹窗或等价管理入口中提供“记忆管理”区域，支持查看、搜索、编辑、停用、删除所有长期记忆。该入口不得与 raw config 编辑混淆；它管理 `memory.json` 的记忆数据，不管理 `config.toml`。

### SPEC-LTM-FE-006：隐私与可解释性

每条自动/候选记忆必须展示来源说明或证据文本；用户应能看出“为什么这条被记住”。敏感/需确认候选不得用暗色弱提示藏起来。

### SPEC-LTM-FE-007：空状态与失败状态

无记忆、无候选、加载失败、保存失败都必须有明确文案。失败不得清空用户正在编辑的记忆文本。

### SPEC-LTM-FE-008：i18n

所有新增前端文案必须进入 `i18n.js` 消息目录，遵守 `SPEC-I18N-UI-*`；不得在 JS/HTML 中写死仅中文或仅英文文案。

---

## 12. 安全与隐私约束

- **SPEC-LTM-SEC-001**：自动提炼 prompt 必须明确禁止保存凭据、密钥、验证码和高敏身份标识。
- **SPEC-LTM-SEC-002**：服务端在写入前必须执行确定性敏感过滤；不能只依赖 LLM 自觉。
- **SPEC-LTM-SEC-003**：用户拒绝的候选不得进入上下文；用户删除的记忆不得保留在 `memory.json`。
- **SPEC-LTM-SEC-004**：记忆管理 UI 必须允许用户停用或删除错误记忆。
- **SPEC-LTM-SEC-005**：LLM 提炼输入不得包含配置敏感值，遵守 `SPEC-CSP-DEC-007` 和 `SPEC-CSP-API-011d` 的隐私边界。

---

## 13. 测试规格

| 规格 ID | 测试场景 | 预期 |
|---------|----------|------|
| SPEC-LTM-TST-001 | 读取旧版 `memory.json`（无 `memories`） | 成功加载，`memories=[]` |
| SPEC-LTM-TST-002 | 保存含 `MemoryItem` 的 profile | pretty JSON，时间为 ISO，旧字段保留 |
| SPEC-LTM-TST-003 | `buildContextSummary()` 含 active/pending/disabled/rejected | 只输出 active 与 legacy 有效内容 |
| SPEC-LTM-TST-004 | 创建重复 content 候选 | 不产生重复 active/pending |
| SPEC-LTM-TST-005 | `confidence` 越界 | 被夹取或拒绝，不能写入非法值 |
| SPEC-LTM-TST-006 | assistant sent 后触发提炼 | 聊天响应不等待提炼完成 |
| SPEC-LTM-TST-007 | 提炼返回非法 JSON | 聊天成功，候选不写入或只写入可解析项 |
| SPEC-LTM-TST-008 | 会话策略 `off` | 不自动提炼 |
| SPEC-LTM-TST-009 | 会话策略 `confirm_all` | 所有候选为 pending |
| SPEC-LTM-TST-010 | 低风险明确偏好，confidence>=8 | smart 模式自动 active |
| SPEC-LTM-TST-011 | 行为模式/敏感推断 | smart 模式 pending |
| SPEC-LTM-TST-012 | 文本包含 API key/token 模式 | 不保存 active/pending |
| SPEC-LTM-TST-013 | 批准 pending 记忆 | 状态变 active，下一轮聊天上下文包含该记忆 |
| SPEC-LTM-TST-014 | 删除 active 记忆 | 从 `memory.json` 移除，下一轮聊天上下文不包含 |
| SPEC-LTM-TST-015 | `/desktop/memory` 写入失败 | 返回 JSON error，不破坏原文件 |
| SPEC-LTM-TST-016 | 前端保存失败 | 编辑文本仍保留，显示错误 |
| SPEC-LTM-TST-017 | 新增 UI 文案 | `i18n.js` 中 zh/en 均有值 |

---

## 14. 追溯矩阵

| 规格 ID | 目标文件/组件 | 验证方式 |
|---------|---------------|----------|
| SPEC-LTM-REL-001..005 | `docs/specs/core.md`、`docs/specs/chat-session-store.md`、`docs/specs/desktop-chat-tab.md` | spec 审查 |
| SPEC-LTM-DEC-001..008 | 全特性 | spec 审查、代码审查 |
| SPEC-LTM-GOAL-001..007 | 全特性 | 单元测试 + 手动验收 |
| SPEC-LTM-NON-001..006 | 全特性 | 代码审查 |
| SPEC-LTM-MDL-001..007 | `memory/GrowthProfile.java`、`memory/MemoryStore.java` | `MemoryStoreTest`、新增 `GrowthProfileMemoryItemTest` |
| SPEC-LTM-MDL-010..011 | `desktop/store/ChatSessionStore.java` | `ChatSessionStoreTest` |
| SPEC-LTM-EXTR-001..009 | 新增长期记忆提炼服务、`DesktopChatSessionController.java`、`SelfAnalystAgent.java` | 新增提炼服务单测、控制器单测 |
| SPEC-LTM-PROMPT-001..005 | `agent/SelfAnalystAgent.java`、`agent/AgentPrompts.java`、`memory/GrowthProfile.java` | `AgentPromptsTest`、新增 prompt 记忆测试 |
| SPEC-LTM-API-001..009 | 新增 `DesktopMemoryController.java`、`DesktopServer.java`、`MemoryStore.java` | 控制器单测、store 单测 |
| SPEC-LTM-FE-001..008 | `desktop-ui/api.js`、`state.js`、`chat.js`、`index.html`、`styles.css`、`i18n.js` | JS 单测、手动 UI 验收 |
| SPEC-LTM-SEC-001..005 | 提炼服务、记忆写入服务、前端记忆管理 UI | 安全过滤单测、代码审查 |
| SPEC-LTM-TST-001..017 | 测试套件 | Maven/JS 测试 |
