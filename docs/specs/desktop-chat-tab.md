# SelfAnalyst 桌面会话页 SDD 规格说明书

> **迁移状态：** 现行行为契约已迁移至 [`desktop-chat`](../../openspec/specs/desktop-chat/spec.md)。本文档仅保留为旧 ID、历史背景和源码追溯，不再独立维护。

> 本文档定义桌面会话页的当前 UI、交互和发送语义。会话数据模型与 REST 契约见
> [`chat-session-store.md`](chat-session-store.md)，SQLite 实现见
> [`chat-session-sqlite-store.md`](chat-session-sqlite-store.md)。

## 1. 文档元信息

| 属性 | 值 |
|------|-----|
| 功能 | 多会话聊天、Deep Chat 消息面、流式响应、上下文与任务建议 |
| 状态 | 已实现（当前契约） |
| 规格前缀 | `SPEC-CHAT-TAB-*` |
| 前端 | 原生 HTML/CSS/JS；无打包器 |
| 消息组件 | vendored Deep Chat 2.5.0；失败时回退原生消息面 |
| 权威存储 | `{memoryDir}/chat-sessions/chat.db` |

## 2. 总体边界

- Deep Chat 只拥有消息显示和输入组件；SelfAnalyst 仍拥有会话、服务端 ID、持久化、重试、取消和业务上下文。
- 前端 `state.chatSessions` 只是分页元数据与已懒加载正文的运行时缓存，不是持久化事实来源。
- UI transcript 以 `chat.db` 为权威；模型历史以 AgentState 为权威。前端不得把完整 transcript 每轮重复注入 prompt。
- 浏览器 `localStorage` 旧会话不迁移，也不得重新成为持久化层。
- 所有 `/desktop/*` 请求沿用桌面认证 header/cookie，不在聊天模块自行复制 token 逻辑。

## 3. 信息架构

### SPEC-CHAT-TAB-001：导航入口

- 顶部导航包含 Agent、会话和配置入口；会话页不取代 Agent 首页。
- 从 Agent 摘要、时间线或建议入口进入会话页时，可以携带本轮业务上下文和预填问题。

### SPEC-CHAT-TAB-002：布局

会话页由会话列表、主消息区和可选上下文/记忆区组成：

- 左侧列表支持新建、选择、重命名、删除、搜索和继续加载；
- 主消息区使用 `<deep-chat>`，升级失败或超时时显示原生 fallback；
- 窄窗口允许面板折叠或纵向重排，不得造成不可操作的横向溢出；
- 消息列表、会话列表和上下文区域分别可滚动。

## 4. 会话生命周期

### SPEC-CHAT-TAB-003：创建与激活

- 新会话通过 `POST /desktop/chat/sessions` 创建，ID、时间戳和初始字段全部使用服务端返回值。
- 前端不得预生成会话或消息 ID。
- 激活会话通过 `PUT /desktop/chat/active-session` 持久化；刷新或重启后恢复后端 active 指针。
- 从上下文入口发送首条消息时，如当前没有会话，先创建会话再发送。

### SPEC-CHAT-TAB-004：列表、搜索与正文懒加载

- 初始调用 `GET /desktop/chat/sessions?limit=50`，按 `updatedAt DESC, id DESC` 展示。
- `nextCursor/hasMore` 驱动继续加载；搜索通过 `q` 交给后端 FTS5/LIKE，不在前端扫描全部正文。
- 列表只缓存元数据；选中会话后调用 `GET /desktop/chat/sessions/{id}` 懒加载完整消息。
- 并发加载必须使用 request generation/当前会话检查丢弃过期响应，不能让慢响应覆盖新选择或新 mutation。
- 删除前二次确认；成功后只使用服务端返回和重新选择规则更新缓存。409/失败时保留当前数据。

## 5. 消息发送、流式响应与恢复

### SPEC-CHAT-TAB-005：发送顺序

一次新消息必须按以下顺序执行：

1. 确认 active 会话存在且正文已加载；
2. 向 `POST /desktop/chat/sessions/{id}/messages` 原子追加 server-owned user 与 pending assistant；
3. 使用返回的 `sessionId`、`userMessageId` 和业务 `context` 调用 `/desktop/chat/stream`；
4. 把 delta 只显示到发起请求的会话；
5. 完成后用后端 canonical message 更新同一 pending，不追加第二条 assistant；若 canonical
   文本为空但已有非空 delta，则必须先把 delta 写回 AgentState terminal assistant，再以其作为
   canonical 文本；无法完成一致持久化或两者都为空时必须失败；
6. 刷新会话元数据、摘要和排序。

同一时刻前端只允许一个发送流程。切换会话不会改变执行所属 session，也不得把 delta 写到新会话。
停止按钮先调用 `/desktop/chat/sessions/{id}/cancel`，再中断浏览器流；取消失败或超时后仍应终止本地读取。

### SPEC-CHAT-TAB-006：业务上下文

- 请求上下文可包含来源、时间范围、时间线条目、建议任务和用户选择的记忆信息。
- 上下文必须经过深度、节点、容器宽度和字节预算约束；超限内容由后端规范化。
- 上下文不得包含 API key、完整配置或 UI transcript。
- 本轮上下文通过 Agent RuntimeContext 临时注入，不写入持久 user Msg。

### SPEC-CHAT-TAB-007：跨入口联动

- Agent 页“继续追问”等入口切换到会话页、设置上下文并聚焦输入框。
- 若入口提供预填问题，只填草稿，不在用户确认前自动发送。
- legacy chat drawer 可以保留兼容，但同一次操作不能同时打开 drawer 和会话页。

### SPEC-CHAT-TAB-008：建议任务

- assistant 消息中的 `suggestedTasks` 由服务端规范化并持久化。
- Deep Chat action button 或 fallback 按钮可把单项建议转换为任务；创建成功后禁用/标记该操作。
- 渲染按钮时只使用服务端消息 ID、规范化任务和转义文本，不允许模型返回任意 HTML。

### SPEC-CHAT-TAB-009：上下文与长期记忆

- 上下文面板显示本轮来源、时间范围和已选信息，不回显配置敏感值。
- 会话级记忆策略、候选确认和长期记忆管理遵循 `long-term-memory.md`。
- 删除会话不会隐式删除已经独立保存的长期记忆。

## 6. 降级与错误

### SPEC-CHAT-TAB-010：LLM 不可用

- 状态接口显示 LLM 未配置/不可用时禁用发送，并提供可操作提示；会话浏览、搜索、删除和记忆管理仍可用。
- 不得显示永久 loading 或静默吞掉输入。

### SPEC-CHAT-TAB-011：失败、重试与 pending 恢复

- 输入在持久化前失败时保留为编辑器草稿，并显示瞬态错误，不伪造已保存消息。
- user/pending 已持久化后失败时，把原 pending 更新为 error；重试复用原 session/user/pending IDs。
- 模型未产生最终文本时不得写入“无响应”等占位文本或把 pending 标记为 sent；后端返回
  502/SSE error，前端把原 pending 更新为 error 并保留重试入口。即使后端错误地返回成功但
  消息字段为空或不是字符串，前端也必须执行同样的 error 转换。
- 刷新后发现 pending 时，只允许恢复最新未完成 turn；如果 AgentState 已有 terminal 回复，后端返回既有结果，不再次调用模型。
- busy 返回 409；网络、500、解析和流中断必须提供重试入口，不能重复 user turn。
- 会话切换、删除或新加载产生的陈旧响应必须被 generation/ID 检查丢弃。
- Deep Chat custom element 未升级或内部消息面不可用时，在 2 秒内切换 fallback；持久化与 API 语义保持不变。

### SPEC-CHAT-TAB-012：键盘、焦点与可访问性

- 桌面端 Enter 发送，Shift+Enter 换行；输入法组合期间 Enter 不发送。
- 切入会话页或从上下文入口进入后聚焦 composer。
- 按钮必须可键盘操作并具有可读标签；disabled/loading/error 不能只依赖颜色表达。
- 所有用户和模型纯文本按文本节点或关闭 HTML 的 Markdown 渲染；结构化 HTML 必须由本地转义模板生成。

## 7. 前端文件与加载顺序

当前桌面前端使用多个全局脚本，不存在 `desktop-ui/app.js`：

```text
deep-chat.bundle.js（module）→ utils.js → i18n.js → state.js → api.js → agent.js
→ deep-chat-adapter.js → chat.js → chat-drawer.js
→ config.js → ui.js → events.js → init.js
```

关键职责：

- `chat.js`：会话缓存、分页、懒加载、发送/重试/恢复与 fallback；
- `deep-chat-adapter.js`：Deep Chat 生命周期、canonical 同步、stream handler、stop 和 action；
- `api.js`：会话 REST、stream、cancel；
- `state.js`：请求 generation、active session 和 UI 状态；
- `deep-chat.bundle.js`：第三方 vendored 产物，不直接承载业务权威状态。

## 8. 资源与安全边界

- 前端单次输入最多 20,000 字符，Deep Chat 显示最多 200 条；服务端仍是最终限额权威。
- mutation body、JSON 深度、opaque context、建议任务和单会话预算遵循 `chat-session-store.md`。
- 模型文本禁用 raw HTML；外部链接使用新窗口；配置密钥不得进入消息、AgentState、上下文或日志。
- UI 只使用后端 canonical 消息更新本地缓存，不以整块缓存覆盖数据库。

## 9. 测试与验收

- `scripts/check-desktop-chat-tab.ps1`
- `scripts/check-desktop-chat-session-store.ps1`
- Node UI 测试覆盖分页、过期响应、stream、空响应、取消、重试、pending 恢复、Deep Chat fallback 和 XSS 转义。
- 后端测试覆盖 server-owned IDs、幂等、空响应 502 与同 turn 重试、busy 409、AgentState 恢复、取消和删除协调。
- 手动验证 800×600、宽屏、输入法、键盘、慢流、断网、切换会话和重载。

## 10. 追溯矩阵

| 规格 ID | 文件/组件 | 验证 |
|---------|-----------|------|
| SPEC-CHAT-TAB-001..002 | `index.html`、`ui.js`、`styles.css` | 静态检查、UI 验收 |
| SPEC-CHAT-TAB-003..004 | `chat.js`、`api.js`、`state.js` | Node 测试、后端 REST 测试 |
| SPEC-CHAT-TAB-005 | `chat.js#sendChatTabMessage`、`deep-chat-adapter.js#handleDeepChatRequest`、stream controller | stream/取消/幂等测试 |
| SPEC-CHAT-TAB-006..009 | `chat.js`、`deep-chat-adapter.js`、长期记忆 controller | 上下文、任务和记忆测试 |
| SPEC-CHAT-TAB-010..011 | `chat.js`、`deep-chat-adapter.js`、Agent lifecycle | 失败、重试、pending、fallback 测试 |
| SPEC-CHAT-TAB-012 | `deep-chat-adapter.js`、`events.js`、`styles.css` | 键盘、可访问性和转义测试 |
