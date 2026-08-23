# SelfAnalyst 桌面端会话 Tab SDD 规格说明书

> Specification-Driven Development spec. 本文档面向自动化实现工具和后续评审人员，要求实现者按规格逐项落地，不依赖口头上下文。

---

## 1. 文档元信息

| 属性 | 值 |
|------|-----|
| 功能名称 | 桌面端新增 `会话` tab |
| 文档状态 | Ready for implementation |
| 日期 | 2026-06-06 |
| 目标平台 | SelfAnalyst Tauri 桌面端内嵌 WebView，Windows 优先 |
| 参考设计稿 | `docs/mockups/desktop-chat-tab-v1.png` |
| 主要前端入口 | `self-analyst-app/src/main/resources/desktop-ui/index.html` |
| 主要前端逻辑 | `self-analyst-app/src/main/resources/desktop-ui/` (init/ui/chat/config/agent/events/api/state/utils/chat-drawer.js) |
| 主要样式文件 | `self-analyst-app/src/main/resources/desktop-ui/styles.css` |
| 前端源文件 | `self-analyst-app/src/main/resources/desktop-ui/index.html` 等，Tauri `frontendDist` 直接指向此目录 |

---

## 2. 背景和当前状态

当前桌面端 UI 已从完整 ActivityWatch Web UI 简化为轻量桌面工作台:

- `Agent` tab: 默认主动总结面板，展示当前状态、时间轴、未来任务。
- `配置` tab: 支持界面内编辑和保存配置。
- 现有聊天能力: `Agent` tab 通过右侧/底部聊天抽屉调用 `POST /desktop/chat`，用于对当前状态、时间轴条目或任务进行追问。

当前用户希望新增一个独立 `会话` tab，使聊天从辅助抽屉升级为完整会话工作区。该 tab 不替代 Agent 的主动总结，也不替代配置页。它的职责是承载持续对话、追问、上下文引用、待办生成和会话记录。

当前 Tauri 配置实际加载:

```text
http://localhost:5700/desktop-ui/
```

因此运行时主要读取 `self-analyst-app/src/main/resources/desktop-ui/*` 中的静态资源。Tauri `frontendDist` 直接指向此目录，无需维护副本。

---

## 3. 设计结论

新增第三个顶层 tab:

```text
SelfAnalyst    Agent    会话    配置                         服务 采集 LLM
```

默认 tab 仍为 `Agent`。`会话` tab 采用三栏布局:

1. 左侧: 会话列表和会话偏好。
2. 中间: 主会话线程和输入框。
3. 右侧: 当前会话上下文、最近活动、可生成待办、上下文开关。

设计稿路径:

```text
docs/mockups/desktop-chat-tab-v1.png
```

视觉和交互必须以该 PNG 为基准，但实现时优先遵守本文档中的约束和验收标准。

---

## 4. 目标

- 用户可以在桌面端通过独立 `会话` tab 与 SelfAnalyst 进行持续对话。
- 用户可以创建、切换、继续由本机后端持久化的会话。
- 会话可按开关引用当前状态、今日活动和未来任务；同一会话的模型历史由 AgentState 自动恢复，不提供历史消息开关，也不把 UI transcript 放入 `context.history`。
- Agent 回复中的建议可以转成待办任务，必须由用户点击确认后创建。
- `Agent` tab 中已有的 `追问` 行为应能进入 `会话` tab，并自动带入被追问对象的上下文。
- 在 LLM 不可用、请求失败、网络超时、没有历史会话等情况下，界面应有清晰降级状态。
- 第一版不要求流式输出，但 UI 应能显示发送中状态，避免重复提交。

---

## 5. 非目标

- 不在第一版实现云同步、跨设备同步或账号系统。
- 不在第一版实现多人协作、文件附件、图片上传、语音输入。
- 不新增复杂项目管理功能，例如任务依赖、甘特图、循环任务。
- 不实现远程或云端会话服务；会话正文必须使用 localhost Java 后端提供的分片存储与 REST CRUD。
- 不改动 ActivityWatch 完整 Web 仪表盘。
- 不改变 `Agent` tab 的默认首页定位。
- 不让 Agent 未经确认静默创建任务。

---

## 6. 信息架构

### 6.1 顶部导航

现有导航:

```html
<button class="tab active" data-tab="agent">Agent</button>
<button class="tab" data-tab="config">配置</button>
```

必须改为:

```html
<button class="tab active" data-tab="agent">Agent</button>
<button class="tab" data-tab="chat">会话</button>
<button class="tab" data-tab="config">配置</button>
```

导航行为:

- 初次打开仍选中 `Agent`。
- 点击 `会话` 切换到 `#tab-chat`。
- 点击 `配置` 仍延迟加载配置数据。
- 当前 tab 使用现有 `.tab.active` 样式。
- 不新增第四个 tab，不把 Web 仪表盘做成 tab。

### 6.2 新增会话 tab DOM

在 `#main-content` 中，在 `#tab-agent` 和 `#tab-config` 之间插入:

```html
<section id="tab-chat" class="tab-content">
  ...
</section>
```

推荐 DOM 结构:

```html
<section id="tab-chat" class="tab-content">
  <div class="chat-tab-layout">
    <aside class="chat-session-sidebar">
      <div class="chat-session-header">...</div>
      <div class="chat-session-search">...</div>
      <div id="chat-session-list" class="chat-session-list"></div>
      <div class="chat-session-preferences">...</div>
    </aside>

    <section class="chat-workspace">
      <div class="chat-workspace-header">...</div>
      <div id="chat-thread" class="chat-thread"></div>
      <div class="chat-composer">...</div>
    </section>

    <aside class="chat-context-panel">
      <div id="chat-context-summary">...</div>
      <div id="chat-recent-activity">...</div>
      <div id="chat-task-suggestions">...</div>
      <div id="chat-context-toggles">...</div>
    </aside>
  </div>
</section>
```

Required element IDs:

| ID | 用途 |
|----|------|
| `tab-chat` | 顶层会话 tab 内容容器 |
| `chat-session-list` | 左侧会话列表 |
| `new-chat-session-btn` | 新建会话按钮 |
| `chat-session-search-input` | 搜索会话输入框 |
| `chat-thread` | 主消息流容器 |
| `chat-tab-input` | 主会话输入框 |
| `chat-tab-send-btn` | 主会话发送按钮 |
| `chat-context-summary` | 右侧当前状态上下文 |
| `chat-recent-activity` | 右侧最近活动列表 |
| `chat-task-suggestions` | 右侧可生成待办列表 |
| `chat-context-toggles` | 右侧上下文开关区域 |

实现者可以添加更多 class 或 ID，但不得遗漏上述 ID。

---

## 7. 视觉规格

### 7.1 总体风格

必须沿用当前 `styles.css` 的暗色主题变量:

- 背景: `--bg-primary`
- 顶栏: `--bg-secondary`
- 面板: `--bg-card`
- 输入: `--bg-input`
- 边框: `--border-color`
- 主文字: `--text-primary`
- 次级文字: `--text-secondary`
- 强调色: `--accent`

不得引入新的大面积渐变、装饰性背景、营销式 hero 或浮夸视觉。该界面是工作型桌面工具，重点是信息密度、稳定布局和可扫描性。

### 7.2 布局尺寸

在 1920x1220 设计稿中推荐尺寸:

| 区域 | 宽度 | 说明 |
|------|------|------|
| 左侧会话列表 | 330px | 固定宽度 |
| 中间主会话 | 自适应，约 990px | 主工作区 |
| 右侧上下文 | 约 480px | 固定/弹性宽度 |
| 列间距 | 24px | 与当前桌面页一致 |
| 外边距 | 24px | 与当前桌面页一致 |

CSS 推荐:

```css
.chat-tab-layout {
  display: grid;
  grid-template-columns: 330px minmax(480px, 1fr) minmax(360px, 480px);
  gap: 24px;
  height: 100%;
}
```

### 7.3 响应式

Tauri 当前最小窗口约 800x600。必须处理窄宽度:

- `>= 1400px`: 三栏完整展示。
- `1100px - 1399px`: 左侧会话列表 + 中间主会话展示，右侧上下文折叠为按钮或置于主会话下方。
- `< 1100px`: 单列布局，顺序为会话列表、主会话、上下文；会话列表最大高度约 220px，可滚动。

不得让输入框、按钮、任务卡片文字互相重叠。所有固定区域必须有 `min-width: 0` 或可换行策略。

### 7.4 组件圆角和卡片

- 面板圆角使用当前系统的 `6px-10px` 范围。
- 不要在卡片里再放完整卡片式页面容器。消息中的待办建议可用轻量 row/card，但不得形成多层嵌套卡片视觉。
- 按钮使用现有 `.btn`, `.btn-sm`, `.btn-primary`, `.btn-outline`, `.btn-icon` 风格。

---

## 8. 数据模型

### 8.1 前端状态

扩展 `state`:

```js
var state = {
  tab: "agent",
  status: null,
  summary: null,
  tasks: [],
  config: null,

  chatSessions: [],
  activeChatSessionId: null,
  chatSessionSearch: "",
  chatSending: false,
  chatError: null,
  chatContextToggles: {
    currentStatus: true,
    futureTasks: true
  }
};
```

现有 `chatOpen`, `chatContext`, `chatMessages` 可保留用于兼容旧抽屉。新增 `会话` tab 中，`state.chatSessions` 只是后端索引和已懒加载分片的运行期缓存：UI 可见 transcript 的权威数据位于 `{memoryDir}/chat-sessions/`，模型执行历史的权威数据位于 AgentState。实现者可以在后续重构中删除抽屉，第一版不强制删除。

### 8.2 会话数据结构

```ts
type ChatSession = {
  id: string;              // 服务端生成的 lowercase hex32
  title: string;
  createdAt: string;       // 服务端生成的 ISO timestamp
  updatedAt: string;       // 服务端维护的 ISO timestamp
  source: "manual" | "agent_context" | "task_context";
  contextLabel?: string;
  contextSnapshot?: ChatContextSnapshot;
  messages: ChatMessage[];   // 仅完整分片响应包含正文；索引缓存初始为空
  messagesLoaded?: boolean;  // 仅前端运行期标记，不持久化
  messagesLoading?: boolean;
  messagesLoadError?: string;
};
```

### 8.3 消息数据结构

```ts
type ChatMessage = {
  id: string;              // 服务端生成的 lowercase hex12
  role: "user" | "assistant" | "system";
  content: string;
  createdAt: string;       // 服务端生成的 ISO timestamp
  status?: "pending" | "sent" | "error";
  error?: string;
  contextSnapshot?: ChatContextSnapshot;
  suggestedTasks?: SuggestedTask[];
};
```

### 8.4 上下文快照

```ts
type ChatContextSnapshot = {
  type: "manual" | "current_status" | "timeline_entry" | "task" | "global";
  id?: string;
  title?: string;
  headline?: string;
  summary?: string;
  evidence?: string[];
  periodStart?: string;
  periodEnd?: string;
  currentStatus?: unknown;
  recentActivity?: unknown[];
  futureTasks?: unknown[];
};
```

约束:

- 上下文快照应保存当次发送时的上下文，而不是发送后实时变化的引用。
- 发送给后端的 `context` 必须是可 JSON 序列化的普通对象。
- 避免把完整 `state.summary` 原样无限塞入每次请求。应提取必要字段，控制 payload 大小。

### 8.5 建议任务结构

前端应兼容后端当前可能返回的字段:

```ts
type SuggestedTask = {
  title: string;
  notes?: string;
  priority?: "low" | "medium" | "high";
  dueAt?: string | null;
};
```

兼容来源:

- `resp.suggestedTasks`
- `resp.suggested_tasks`
- `resp.tasks`

---

## 9. 服务端分片持久化与模型状态

完整 REST 与落盘契约由 [`chat-session-store.md`](chat-session-store.md) 定义。本文件只保留会话 tab 必须遵守的集成约束。

- UI 可见的会话正文以 `{memoryDir}/chat-sessions/` 为权威：`index.json` 保存 active 指针和列表投影，`index.state` 记录跨文件恢复状态，每个 lowercase hex32 `sessionId` 对应一个 shard。
- 前端初始化只加载 50 条索引元数据；active 不在首屏时单独加载，激活其它会话时再懒加载正文。`state.chatSessions` 仅是分页渲染缓存。
- 创建、切换 active、重命名、删除、追加消息和更新 pending 状态全部通过细粒度 REST 接口完成。客户端不得生成或覆盖会话 ID、消息 ID、`createdAt` 或 `updatedAt`。
- 会话数量不设上限。单会话最多 200 条消息且按完整 user turn 保留；单条输入 `content` 最多保留 20000 code point 后追加 `...`。opaque 快照、建议任务、请求体和最终 shard 同样受服务端资源预算约束。
- 模型历史以 `{memoryDir}/agent-state/self-analyst-chat/desktop/<sessionId>/` 下的 AgentState 为权威。UI transcript 不得在每轮请求中重新注入模型上下文。
- 旧 WebView key `selfAnalyst.chatSessions.v1` 不读取、不迁移，并在升级初始化时删除。
- 对已经存在于服务端分片、但尚无 AgentState 的旧会话，后端在该会话下一次发送时执行一次懒迁移：只导入当前 user 消息之前的有效 user/sent assistant turn，排除 UI-only system、pending 和 error 消息；AgentState 已存在时不得重复导入。
- 不得把 API Key、完整配置或其它配置敏感值写入会话分片、AgentState、上下文快照或日志。

---

## 10. API 契约

### 10.1 复用现有聊天接口

LLM 调用继续复用既有 `POST /desktop/chat`；会话 CRUD 路由由 [`chat-session-store.md`](chat-session-store.md) 定义。会话 tab 在已有聊天请求体顶层增加必传的 `sessionId` 与 `userMessageId`：

```http
POST /desktop/chat
Content-Type: application/json

{
  "message": "帮我看一下今天下午应该优先处理什么？",
  "sessionId": "0123456789abcdef0123456789abcdef",
  "userMessageId": "0123456789ab",
  "context": {
    "type": "global",
    "title": "会话 tab",
    "currentStatus": {},
    "futureTasks": []
  }
}
```

- 会话 tab 必须同时传服务端生成的 lowercase hex32 `sessionId`，以及消息追加接口返回、确实属于该分片 user 消息的 lowercase hex12 `userMessageId`；后端据此选择 AgentScope `RuntimeContext` / `AgentState` 槽位并校验 turn 归属。
- 只有旧聊天抽屉与旧客户端可同时省略这两个字段，落入固定的 legacy 会话槽。
- `sessionId` 是路由元数据，不得放进发送给模型的语义 `context`。
- `userMessageId` 使用消息追加接口返回的 hex12 ID；重试沿用同一 ID，后端据此返回已完成回复或从未完成 turn 继续，避免重复 user turn。
- ID 格式或 turn 归属非法返回 HTTP 400；会话不存在返回 404；Agent 正在处理另一请求时返回 HTTP 409 与 `{ "error": "..." }`。409 是可重试失败，不得作为 assistant 成功回复写入 transcript。

成功响应:

```json
{
  "message": "建议先处理...",
  "suggestedTasks": [
    {
      "title": "修复配置保存链路",
      "priority": "high",
      "notes": "需要验证 PUT /desktop/config"
    }
  ]
}
```

兼容响应:

- `message`
- `reply`
- `content`

如果 `message` 不存在，按 `reply`、`content` 顺序兜底。若都不存在，显示 `Agent 未返回可显示内容`。

### 10.2 任务创建接口

用户点击建议任务后调用:

```http
POST /desktop/tasks
Content-Type: application/json

{
  "title": "修复配置保存链路",
  "notes": "需要验证 PUT /desktop/config",
  "priority": "high",
  "source": "chat"
}
```

创建成功后:

- 刷新 `state.tasks`。
- 右侧建议任务行显示已创建状态。
- 不重复创建同一条建议。至少在当前消息内禁用已创建建议按钮。

### 10.3 数据来源

状态、任务和 LLM 调用继续使用现有接口:

- `GET /desktop/status`
- `GET /desktop/summary`
- `GET /desktop/tasks`
- `POST /desktop/chat`
- `POST /desktop/tasks`

会话列表、正文与写操作使用:

- `GET/POST /desktop/chat/sessions`
- `GET/PUT/DELETE /desktop/chat/sessions/{id}`
- `POST /desktop/chat/sessions/{id}/messages`
- `PUT /desktop/chat/sessions/{id}/messages/{msgId}`
- `PUT /desktop/chat/active-session`

切换到 `会话` tab 时:

- 如果 `state.summary` 或 `state.tasks` 为空，允许复用页面初始化时的加载逻辑拉取。
- 不要因为某个接口失败阻断整个 tab。失败区域单独显示降级状态。

---

## 11. 行为规格

### SPEC-CHAT-TAB-001: 新 tab 可见

顶部导航必须出现 `会话` tab，位置在 `Agent` 和 `配置` 之间。

验收:

- 打开 `http://localhost:5700/desktop-ui/` 后能看到 `Agent`, `会话`, `配置` 三个 tab。
- 默认 active 仍为 `Agent`。
- 点击 `会话` 后只显示 `#tab-chat`，隐藏其他 tab content。

### SPEC-CHAT-TAB-002: 首次进入会话页

首次进入且没有历史会话时:

- 左侧显示空会话列表状态或自动创建一个 `当前会话`。
- 中间显示欢迎消息，说明可以追问当前状态、记录想法、生成待办。
- 右侧显示当前状态、最近活动、可生成待办、上下文开关。
- 输入框可用，除非 LLM 未配置。

推荐默认行为: 自动创建一个 `当前会话`，减少空状态。

### SPEC-CHAT-TAB-003: 新建会话

点击 `+ 新建`:

- 调用 `POST /desktop/chat/sessions` 创建一个新的 `ChatSession`，采用响应中的服务端 ID 和时间戳。
- `title` 初始为 `新会话`。
- `source` 为 `manual`。
- 切换为 active session。
- 输入框获得焦点。

首次用户发送消息后，如果 title 仍为 `新会话`，用用户首条消息生成标题:

- 取首条用户消息前 18 个中文字符或 36 个 ASCII 字符。
- 去掉换行。
- 为空则保持 `新会话`。
- 通过 `PUT /desktop/chat/sessions/{id}` 回填标题，不在前端整块覆盖会话。

### SPEC-CHAT-TAB-004: 会话列表

左侧会话列表:

- 按 `updatedAt` 降序排列。
- active session 有明显高亮和左侧强调条。
- 每项展示 title、最后一条消息摘要、更新时间。
- 搜索框 debounce 后调用服务端元数据搜索，按 `title`、`lastMessagePreview` 和 `summary` 过滤并游标分页，不扫描消息正文；search/mutation/list-load 三类请求代次共同阻止乱序搜索、启动期旧列表或过期续页覆盖新交互，失效的启动首屏会安全补载并保留 live session 正文对象。
- 没有匹配结果时显示 `没有匹配的会话`。

会话列表 item 点击后:

- 切换 `activeChatSessionId`。
- 若正文未加载，调用 `GET /desktop/chat/sessions/{id}` 后渲染对应消息。
- 调用 `PUT /desktop/chat/active-session` 持久化 active id。

会话列表 item 悬停时显示删除按钮:
- 点击 X 按钮弹出确认对话框。
- 确认后调用 `DELETE /desktop/chat/sessions/{id}`；成功时同一 Agent 生命周期 gate 内已删除可见分片、AgentState 和 ReActAgent cache，再从前端缓存移除。
- 若 Agent 正在处理聊天，后端返回 409 且不做部分删除；前端保留会话并提示稍后重试。
- 删除成功后使用响应中的 `activeSessionId`；若为空，前端可调用创建接口建立新会话。

### SPEC-CHAT-TAB-005: 发送消息

用户在 `chat-tab-input` 输入消息后:

- 点击 `发送` 或按 `Enter` 发送。
- `Shift+Enter` 插入换行。
- 空白消息不发送。
- 发送中禁用发送按钮，防止重复提交。
- 先通过 `POST /desktop/chat/sessions/{id}/messages` 一次追加 user + pending assistant，并采用响应中的两个服务端消息 ID。
- 将已持久化的 pending assistant 显示为 `思考中...` 或加载态。
- 调用 `POST /desktop/chat`，顶层携带 active session 的 `sessionId` 和刚保存 user 消息的 `userMessageId`。
- 成功后通过 `PUT /desktop/chat/sessions/{id}/messages/{pendingId}` 把原 pending 更新为 `sent`，写回 Agent 回复和建议任务。
- 失败（包括 HTTP 409）后把同一 pending 更新为 `error`，展示错误信息和 `重试`；不得追加第二组 user/pending。

发送成功后:

- 清空输入框。
- 滚动到最新消息。
- 采用后端返回的消息与 session 投影更新时间更新运行期缓存。

### SPEC-CHAT-TAB-006: 上下文构建

每次发送消息时，根据右侧开关构建 context:

```js
{
  type: "global",
  title: activeSession.title,
  currentStatus: state.chatContextToggles.currentStatus ? buildCurrentStatusContext() : null,
  futureTasks: state.chatContextToggles.futureTasks ? buildFutureTasksContext() : null,
  recentActivity: buildRecentActivityContext()
}
```

要求:

- `currentStatus` 从 `state.summary.current` 或当前状态卡片数据提取。
- `futureTasks` 从 `state.tasks` 中提取未完成任务，最多 10 条。
- `recentActivity` 从 `state.summary.timeline` 中提取最近 4 条。
- task/timeline/evidence 只传必要字段并限制单字段长度；服务端还会把本轮桌面上下文限制为 12000 字符。
- 历史消息由 AgentScope `AgentStateStore` 按 `sessionId` 自动恢复，不得再嵌入 `context`，避免重复上下文。
- 会话模式以 shard 中 server-owned user content 与 `contextSnapshot` 为准；本轮快照通过 RuntimeContext 临时注入，不写入 AgentState user Msg。
- 每个字段都可为 `null`，后端必须能处理。

### SPEC-CHAT-TAB-007: 从 Agent tab 追问进入

现有 Agent tab 的追问入口包括:

- 当前状态卡片 `追问`
- 时间轴条目 `追问`
- 任务 `讨论`

这些入口应改为:

1. 创建或复用一个基于上下文的 chat session。
2. 切换到 `会话` tab。
3. 将 `contextSnapshot` 绑定到 session。
4. 在右侧上下文面板展示该上下文。
5. 输入框获得焦点。

建议标题:

- 当前状态: `当前状态追问`
- 时间轴条目: `${entry.label}追问`
- 任务: `任务讨论：${task.title}`

兼容要求:

- 如果实现者暂时保留旧 `chat-drawer`，不得让同一次点击同时打开抽屉和切换 tab。
- 推荐第一版直接把追问入口导向新 `会话` tab，旧抽屉可保留未使用代码，后续清理。

### SPEC-CHAT-TAB-008: 建议任务

Agent 回复如包含建议任务:

- 在 assistant 消息下方展示任务建议 row。
- 右侧 `可生成待办` 区也可展示最近一条回复的建议任务。
- 用户点击建议任务后调用 `POST /desktop/tasks`。
- 创建成功后建议项显示 `已创建`。
- 创建失败显示错误，不清空建议项。

不得自动创建任务。必须由用户点击确认。

### SPEC-CHAT-TAB-009: 右侧上下文面板

右侧面板必须包含:

1. `当前状态`
   - 显示当前正在做什么。
   - 显示 `可追问` 或状态标签。
   - 如果无数据，显示 `暂无足够数据`。

2. `最近活动`
   - 显示最近 4 条 timeline/activity。
   - 每条显示时间/标签和摘要。
   - 如果无 timeline，显示 `暂无活动摘要`。

3. `可生成待办`
   - 显示来自最近 Agent 回复的建议任务，或为空状态。
   - 每条有确认创建按钮或已创建状态。

4. `上下文开关`
   - 当前窗口与活动摘要: 默认开。
   - 未来任务与待办: 默认开。
   - 不提供历史消息开关；同会话模型历史始终由 AgentState 管理。

### SPEC-CHAT-TAB-010: LLM 未配置降级

如果 `state.status.llm` 或配置显示 LLM 不可用:

- 输入框禁用或发送时给出明确提示。
- 中间区域显示 `配置 LLM 后可进行会话`。
- 右侧上下文仍可显示本地摘要和任务。
- `配置` tab 不受影响。

不得让页面长时间停留在 `加载中...`。

### SPEC-CHAT-TAB-011: 请求失败

聊天请求失败时:

- user/pending 尚未成功追加时，composer 原文不得被清空。
- 当前用户消息保留。
- assistant pending 消息变为 error。
- 显示错误文案: `发送失败: <原因>`。
- 提供 `重试` 操作。重试时复用原 `sessionId`、`userMessageId`、pending assistant ID 和同一份 `contextSnapshot`，不得创建新消息记录。
- 会话正文重新加载后若发现遗留 `pending`，必须显示恢复/重试操作或将其规范化为可重试 error，不得永久停留在“思考中”。恢复仍使用上述原 IDs，并按原 user→pending 顺序处理。
- 若上次请求已在 AgentState 中完成但回复尚未写回分片，重试直接取回该 user turn 的 terminal assistant 回复，不再次调用模型或追加 turn。
- Agent 忙返回 HTTP 409；前端按可重试错误处理，不把错误 body 当作 assistant 回复。
- 不创建空 assistant 消息。
- 不删除或覆盖后端分片中已有历史。

### SPEC-CHAT-TAB-012: 键盘和焦点

- 进入 `会话` tab 时，如果窗口宽度足够且存在 active session，输入框自动 focus。
- `Enter`: 发送。
- `Shift+Enter`: 换行。
- `Esc`: 如果未来实现了上下文折叠/弹层，则关闭弹层；不得切回 Agent。
- Tab 键顺序应从会话列表、新建按钮、主输入、发送按钮、上下文开关自然流动。

---

## 12. 样式和 class 规格

新增样式应放在 `styles.css` 中现有聊天抽屉样式附近或其后，建议分段注释:

```css
/* ----- Chat Tab ----- */
```

推荐 class:

| Class | 用途 |
|-------|------|
| `.chat-tab-layout` | 会话 tab 三栏 grid |
| `.chat-session-sidebar` | 左侧会话列表面板 |
| `.chat-session-item` | 会话列表项 |
| `.chat-session-item.active` | 当前会话列表项 |
| `.chat-workspace` | 中间主会话面板 |
| `.chat-workspace-header` | 主会话头部 |
| `.chat-thread` | 消息流 |
| `.chat-tab-message` | 消息通用样式 |
| `.chat-tab-message.user` | 用户消息 |
| `.chat-tab-message.assistant` | Agent 消息 |
| `.chat-tab-message.error` | 错误消息 |
| `.chat-composer` | 输入区 |
| `.chat-composer-toolbar` | 输入区工具条 |
| `.chat-context-panel` | 右侧上下文面板 |
| `.chat-context-section` | 右侧分区 |
| `.chat-context-toggle` | 上下文开关行 |
| `.chat-suggested-task-row` | 建议任务行 |

消息气泡:

- 用户消息靠右，使用 `--accent` 背景。
- Agent 消息靠左，使用深色面板背景。
- pending 状态显示较弱文字和加载提示。
- error 状态使用 `--red-dim` 或红色边框，避免全屏错误。

---

## 13. 实现文件和修改范围

### 13.1 必改文件

1. `self-analyst-app/src/main/resources/desktop-ui/index.html`
   - 新增 `会话` tab 按钮。
   - 新增 `#tab-chat` DOM 结构。

2. `self-analyst-app/src/main/resources/desktop-ui/` (10 个模块文件)
   - `state.js` — 扩展 state（chatSessions, activeChatSessionId 等）
   - `init.js` — cacheDom() 增加 chat tab DOM 元素
   - `ui.js` — switchTab() 支持 `chat`
   - `api.js` — 会话 REST CRUD 与 `postChat(msg, ctx, sessionId, userMessageId)`
   - `chat.js` — 后端索引/分片缓存、渲染、消息发送、pending 恢复、上下文构建
   - `events.js` — 新增消息发送、会话管理、上下文开关等事件处理
   - `agent.js` — 修改追问入口导向 `会话` tab

3. `self-analyst-app/src/main/resources/desktop-ui/styles.css`
   - 新增会话 tab 三栏布局和响应式样式。
   - 不破坏已有 Agent/Config 样式。

4. Java 后端
   - `ChatSessionStore` — `index.json` + `index.state` + 每会话分片、DIRTY 崩溃恢复、服务端 ID、原子写和裁剪不变量。
   - `DesktopChatSessionController` / `DesktopServer` — 注册并实现会话 REST CRUD。
   - `DesktopAgentController` — 校验 `sessionId + userMessageId`、执行旧 server transcript 懒迁移，并把 busy 映射为 409。
   - `SelfAnalystAgent` — 按 `(desktop, sessionId)` 持久化 AgentState、幂等重试和生命周期 gate。

### 13.2 不应修改的范围

- `self-analyst-aw/...`
- `self-analyst-desktop/src-tauri/...`
- `scripts/build-dist.ps1`

旧聊天抽屉须保持向后兼容：它可省略 session/message ID 并使用固定 legacy AgentState 槽，但不得削弱会话 tab 的严格校验。

---

## 14. 推荐实现顺序

1. HTML 结构
   - 增加 nav tab。
   - 增加 `#tab-chat` 基础三栏 DOM。

2. Tab 切换
   - `cacheDom()` 增加 `tabChat` 和 chat tab 元素。
   - `switchTab(tab)` 支持 `chat`。
   - 点击 `会话` 后不触发配置加载。

3. 后端存储与 AgentState
   - 实现分片 store、索引重建、服务端 ID 和会话 CRUD。
   - 以 `(desktop, sessionId)` 隔离并持久化 AgentState；实现旧 server transcript 单次懒迁移、同 `userMessageId` 幂等重试和全局生命周期 gate。
   - 删除会话时在同一 gate 中依次清 cache、删 AgentState、删 shard/索引；即使当前 `agent` 未初始化，也必须直接打开状态存储清除持久化 AgentState。busy 时返回 409 且不做部分删除。

4. 前端状态和持久化通道
   - 增加 `state.chatSessions`, `activeChatSessionId`, `chatSending`, `chatContextToggles`。
   - 实现异步 `loadChatSessions()`, `ensureSessionMessagesLoaded()`, `createChatSession()`, `getActiveChatSession()`；不保留整块保存函数。

5. 渲染
   - 实现 `renderChatTab()`。
   - 实现左侧列表、中间消息、右侧上下文三个渲染函数。
   - 处理空状态、错误状态和重载后遗留 pending 的恢复操作。

6. 发送消息
   - 实现 `sendChatTabMessage()`。
   - 使用 append messages → `api.postChat(msg, ctx, sessionId, userMessageId)` → update pending 的有序流程。
   - 处理 pending/success/error、409 和同 ID retry/recovery。

7. 建议任务
   - 复用 `api.createTask()`。
   - 创建成功后调用现有 `loadTasks()` 或直接刷新 `state.tasks` 后 `renderChatTab()`。

8. Agent tab 入口联动
   - 修改当前状态、时间轴、任务讨论按钮。
   - 调用 `openChatTabWithContext(context)`。

9. CSS 和响应式
   - 先实现 1200x800 可用。
   - 再处理 800x600 不重叠。

10. 验证和打包
   - 浏览器打开 `http://localhost:5700/desktop-ui/` 手动验证。
   - Maven package 确认静态资源进入 jar。

---

## 15. 详细伪代码

### 15.1 初始化

```js
function init() {
  cacheDom();
  bindEvents();
  return Promise.all([loadChatSessions(), loadInitialData()]).then(function () {
    if (state.tab === "chat") renderChatTab();
  });
}
```

`loadChatSessions()` 必须先完成索引请求再允许会话列表渲染；不得从 WebView storage 恢复正文。

### 15.2 切换 tab

```js
function switchTab(tab) {
  state.tab = tab;
  state.dom.tabs.forEach(function (t) {
    t.classList.toggle("active", t.dataset.tab === tab);
  });

  state.dom.tabAgent.classList.toggle("active", tab === "agent");
  state.dom.tabChat.classList.toggle("active", tab === "chat");
  state.dom.tabConfig.classList.toggle("active", tab === "config");

  if (tab === "chat") {
    ensureActiveChatSession()
      .then(ensureSessionMessagesLoaded)
      .then(function () {
        renderChatTab();
        focusChatInputSoon();
      })
      .catch(showChatLoadError);
  }

  if (tab === "config" && !state.config) {
    loadConfig();
  }
}
```

### 15.3 创建会话

```js
function createChatSession(opts) {
  return api.createSession({
    title: opts && opts.title ? opts.title : "新会话",
    source: opts && opts.source ? opts.source : "manual",
    contextLabel: opts && opts.contextLabel,
    contextSnapshot: opts && opts.contextSnapshot,
    initialMessages: opts && opts.initialMessages
  }).then(function (session) {
    // id/createdAt/updatedAt/message ids 均采用服务端响应。
    session.messages = session.messages || [];
    session.messagesLoaded = true;
    state.chatSessions.unshift(session);
    state.activeChatSessionId = session.id;
    renderChatTab();
    return session;
  });
}
```

### 15.4 从上下文打开会话 tab

```js
function openChatTabWithContext(context) {
  return createChatSession({
    title: context.title || "上下文追问",
    source: context.type === "task" ? "task_context" : "agent_context",
    contextLabel: context.label || context.title,
    contextSnapshot: context,
    initialMessages: [{
      role: "system",
      content: "已带入上下文：" + (context.title || context.label || "当前条目")
    }]
  }).then(function () {
    switchTab("chat");
  });
}
```

`initialMessages` 的 ID 与时间戳也由服务端分配。UI-only system 消息保存在可见 transcript，但不会在旧会话懒迁移时导入 AgentState。

### 15.5 发送消息

```js
function sendChatTabMessage() {
  if (state.chatSending) return;
  var input = state.dom.chatTabInput;
  var text = input.value.trim();
  if (!text) return;
  state.chatSending = true;
  input.value = "";

  ensureActiveChatSession().then(ensureSessionMessagesLoaded).then(function (session) {
    var context = buildChatContext(session); // 不包含 history
    return api.appendMessages(session.id, { messages: [
      { role: "user", content: text, status: "sent", contextSnapshot: context },
      { role: "assistant", content: "思考中...", status: "pending" }
    ] }).then(function (saved) {
      var savedUser = saved[0];
      var savedPending = saved[1];
      session.messages.push(savedUser, savedPending);
      renderChatTab();

      return api.postChat(text, context, session.id, savedUser.id).then(function (resp) {
        return api.updateMessage(session.id, savedPending.id, {
          status: "sent",
          content: resp.message || resp.reply || resp.content,
          suggestedTasks: resp.suggestedTasks || resp.suggested_tasks || resp.tasks || []
        });
      }).catch(function (err) {
        return api.updateMessage(session.id, savedPending.id, {
          status: "error",
          error: err.message || String(err)
        });
      });
    });
  }).then(finishChatSend, finishChatSend);
}
```

### 15.6 pending 恢复与重试

```js
function retryChatMessage(pendingId) {
  var session = getActiveChatSession();
  var pending = findMessage(session, pendingId);       // status 为 error 或遗留 pending
  var user = findPrecedingUser(session, pendingId);
  if (!session || !pending || !user || state.chatSending) return;

  state.chatSending = true;
  api.updateMessage(session.id, pending.id, { status: "pending", error: null })
    .then(function () {
      // 不 append 新记录；复用服务端生成的 session/user/pending IDs。
      return api.postChat(
        user.content,
        user.contextSnapshot || buildChatContext(session),
        session.id,
        user.id
      );
    })
    .then(function (resp) {
      return api.updateMessage(session.id, pending.id, {
        status: "sent",
        content: resp.message || resp.reply || resp.content,
        suggestedTasks: resp.suggestedTasks || resp.suggested_tasks || resp.tasks || []
      });
    })
    .catch(function (err) {
      return api.updateMessage(session.id, pending.id, {
        status: "error",
        error: err.message || String(err)
      });
    })
    .then(finishChatSend, finishChatSend);
}
```

渲染从服务端加载的 transcript 时，`error` 和遗留 `pending` 都必须绑定上述恢复入口。相同 `userMessageId` 已在 AgentState 中完成时，`POST /desktop/chat` 返回既有 terminal assistant，而不会再次调用模型。

---

## 16. 边界条件

### 16.1 会话后端不可用

如果会话 REST 接口不可用:

- 显示明确的加载或保存失败提示，不把仅存在于内存的内容伪装为已持久化。
- 保留尚未成功追加的输入文本，允许用户在后端恢复后再次提交。
- 已从服务端加载到运行期缓存的内容可继续只读展示，但不得用整块前端缓存覆盖服务端分片。

### 16.2 LLM 调用慢

如果请求超过 20 秒:

- 当前请求仍显示 pending，并禁止为该 user turn 追加第二组消息。
- 可以继续使用其他 tab；重载后遗留 pending 必须提供恢复/重试操作，并复用原服务端 IDs。
- 后端若仍在处理其它调用返回 409；前端将其持久化为可重试 error。

### 16.3 会话与消息体量

- 会话数量不设上限；前端按 50 条游标页加载并提供“加载更多”，不得把“未加载”解释为“已淘汰”。
- 每个会话只保留最近 200 条且从完整 user turn 起始的消息，由后端写入时裁剪；最终 mutation 后前端重新读取 authoritative session，同时镜像 200 条和 16 MiB 两项规则。
- 单条输入消息最多保留 20000 code point 后追加 `...`；辅助字段、opaque JSON 和最终 UTF-8 shard 还须满足 `chat-session-store.md` 的 DEC-013/014。

### 16.4 数据缺失

右侧上下文不能因为 `summary` 为空报错:

- 当前状态: `暂无足够数据`
- 最近活动: `暂无活动摘要`
- 可生成待办: `暂无建议待办`

### 16.5 API 返回非 JSON

`api.postChat` 当前假设成功响应 JSON。若成功状态但 JSON 解析失败:

- 作为错误消息展示。
- 不丢弃用户消息。
- 不写入空 assistant 成功消息。

---

## 17. 测试规格

### 17.1 静态检查

必须检查:

- `index.html` 包含 `data-tab="chat"`。
- `index.html` 包含 `id="tab-chat"`。
- `ui.js` 的 `switchTab` 支持 `chat`。
- 前端不读取旧 key `selfAnalyst.chatSessions.v1`，且初始化仅执行删除兼容清理。
- `state.js` / `index.html` 不包含 history 上下文开关；`chat.js` 不构造 `context.history`。
- `chat.js` 不生成 session/message ID；`api.postChat` 接受 `sessionId, userMessageId`。
- `styles.css` 包含 `.chat-tab-layout`。
- `self-analyst-desktop/src/*` 副本与 `self-analyst-app/src/main/resources/desktop-ui/*` 保持功能一致。

### 17.2 浏览器手动测试

启动后端后打开:

```text
http://localhost:5700/desktop-ui/
```

用 Chrome DevTools 验证:

1. 默认显示 Agent。
2. 点击 `会话`，三栏布局出现。
3. 点击 `+ 新建`，列表出现新会话。
4. 输入消息，`Enter` 发送，Network 依次出现 `POST .../messages`、`POST /desktop/chat`、`PUT .../messages/{pendingId}`；聊天请求携带服务端 `sessionId + userMessageId`。
5. 发送中按钮禁用。
6. 成功后消息出现在会话中。
7. 刷新页面后，会话仍存在。
8. 搜索会话能过滤列表。
9. 建议任务点击后调用 `POST /desktop/tasks`。
10. 点击配置 tab 再回会话 tab，active session 保持。
11. 模拟回复已写入 AgentState、pending 尚未落定后刷新；恢复同一 pending 时不再次调用模型，并将原 pending 更新为 sent。
12. 模拟持久化遗留 pending；刷新后可见恢复/重试操作，且不追加第二条 user 或 assistant。
13. 并发发送或删除正在处理的会话返回 409，UI 保留原记录并允许稍后重试。
14. 给已有 server shard 删除对应 AgentState 后发送下一条消息；仅一次迁移当前 user 之前的有效 user/sent assistant，排除 system/pending/error。

### 17.3 Agent tab 联动测试

1. 在 `Agent` tab 点击当前状态 `追问`。
2. 页面切换到 `会话` tab。
3. 新会话标题为 `当前状态追问` 或等价标题。
4. 右侧上下文显示当前状态。
5. 输入框获得焦点。

对时间轴条目和任务讨论重复上述流程。

### 17.4 窄窗口测试

将窗口缩小到约 800x600:

- 页面不能横向溢出到不可操作。
- 输入框和发送按钮不重叠。
- 会话列表、主会话、上下文至少能纵向滚动访问。
- 顶部 tab 文本不被遮挡。

### 17.5 构建测试

运行:

```powershell
mvn -f D:\aicode\self-analyst\pom.xml -pl self-analyst-app -am package
```

验收:

- Maven exit code 为 0。
- `self-analyst-app/target/classes/desktop-ui/index.html` 包含 `tab-chat`。
- 打包后的 `self-analyst-app/target/self-analyst-app-1.0.0.jar` 内包含更新后的 `desktop-ui/index.html`, `desktop-ui/app.js`, `desktop-ui/styles.css`。

---

## 18. 验收标准

功能完成必须同时满足:

- `会话` tab 出现在顶部导航中，默认不抢占 `Agent` 首页。
- `会话` tab 能创建、切换、搜索后端本地分片会话。
- 消息发送使用现有 `POST /desktop/chat`。
- 会话历史刷新页面后仍存在。
- 会话 tab 的聊天请求使用服务端 `sessionId + userMessageId`，模型历史以 AgentState 为权威且不重复注入 UI transcript。
- 重载后遗留 pending 可用原 IDs 恢复；重复请求不会重复调用模型或追加 user turn。
- Agent 忙时返回 409；删除会话在同一生命周期 gate 中清理 shard、cache 和 AgentState，agent 未初始化时也不遗留持久化状态。
- Agent 回复中的建议任务可由用户点击创建为待办。
- Agent tab 的追问入口能跳转到 `会话` tab 并带入上下文。
- LLM 不可用时界面清晰提示，不出现永久加载。
- 800x600 到宽屏范围内无关键内容重叠。
- Maven package 通过。
- 后端接口仅限本 spec 与 `chat-session-store.md` 定义的会话 CRUD 和向后兼容聊天扩展。
- 不破坏现有 Agent tab、配置 tab、Web 仪表盘按钮。

---

## 19. 规格追溯矩阵

| 规格 ID | 文件/函数 | 验收方式 |
|---------|-----------|----------|
| SPEC-CHAT-TAB-001 | `index.html`, `switchTab` | 点击 tab 手动测试 |
| SPEC-CHAT-TAB-002 | `renderChatTab` | 首次进入会话页 |
| SPEC-CHAT-TAB-003 | `createChatSession`, `api.createSession` | 服务端 ID 新建会话测试 |
| SPEC-CHAT-TAB-004 | `renderChatSessionList`, session REST CRUD | 切换、懒加载、搜索、删除测试 |
| SPEC-CHAT-TAB-005 | `sendChatTabMessage`, `api.postChat` | 三段式 Network 顺序 + 服务端 IDs + UI 状态 |
| SPEC-CHAT-TAB-006 | `buildChatContext` | 请求 payload 检查 |
| SPEC-CHAT-TAB-007 | `openChatTabWithContext` | Agent tab 追问测试 |
| SPEC-CHAT-TAB-008 | `renderSuggestedTasks`, `api.createTask` | 建议转任务测试 |
| SPEC-CHAT-TAB-009 | `renderChatContextPanel` | 右侧上下文检查 |
| SPEC-CHAT-TAB-010 | LLM 状态判断 | LLM 未配置测试 |
| SPEC-CHAT-TAB-011 | `retryChatMessage`, Agent lifecycle gate | 模拟 500/409/断网、pending 重载恢复、同 ID 幂等测试 |
| SPEC-CHAT-TAB-012 | event binding | 键盘交互测试 |

---

## 20. 实现注意事项

- 当前前端不是 React/Vue 项目，是原生 HTML/CSS/JS。不要引入新框架。
- 继续使用 ES5/兼容性较好的写法，保持现有 `app.js` 风格。
- 手写 HTML 字符串时必须使用现有 `escHtml()` 处理用户/Agent 文本。
- 不要把 LLM 返回内容直接赋给 `innerHTML`。
- 不要把 API Key、配置敏感值、完整用户配置写入 chat shards、AgentState、上下文快照或日志。
- 不要修改用户已有任务数据结构，建议任务转待办时只使用后端已支持字段。
- 新增 CSS 不得影响 `.chat-drawer` 除非明确迁移旧抽屉。
- 如果实现者决定删除旧 chat drawer，必须同时删除 HTML、CSS、JS 引用并完成回归测试；第一版不要求删除。
