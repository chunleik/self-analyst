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
- 用户可以创建、切换、继续本地会话。
- 会话可引用当前状态、今日活动、未来任务和可选历史会话上下文。
- Agent 回复中的建议可以转成待办任务，必须由用户点击确认后创建。
- `Agent` tab 中已有的 `追问` 行为应能进入 `会话` tab，并自动带入被追问对象的上下文。
- 在 LLM 不可用、请求失败、网络超时、没有历史会话等情况下，界面应有清晰降级状态。
- 第一版不要求流式输出，但 UI 应能显示发送中状态，避免重复提交。

---

## 5. 非目标

- 不在第一版实现云同步、跨设备同步或账号系统。
- 不在第一版实现多人协作、文件附件、图片上传、语音输入。
- 不新增复杂项目管理功能，例如任务依赖、甘特图、循环任务。
- 不要求新增 Java 后端会话存储 API。第一版会话记录使用 WebView/localStorage 本地保存。
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
    futureTasks: true,
    history: false
  }
};
```

现有 `chatOpen`, `chatContext`, `chatMessages` 可保留用于兼容旧抽屉，但新增 `会话` tab 必须以 `chatSessions` 作为主数据源。实现者可以在后续重构中删除抽屉，第一版不强制删除。

### 8.2 会话数据结构

```ts
type ChatSession = {
  id: string;
  title: string;
  createdAt: string;       // ISO timestamp
  updatedAt: string;       // ISO timestamp
  source: "manual" | "agent_context" | "task_context";
  contextLabel?: string;
  contextSnapshot?: ChatContextSnapshot;
  messages: ChatMessage[];
};
```

### 8.3 消息数据结构

```ts
type ChatMessage = {
  id: string;
  role: "user" | "assistant" | "system";
  content: string;
  createdAt: string;       // ISO timestamp
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

## 9. 本地持久化

第一版使用 WebView/localStorage。

Storage key:

```text
selfAnalyst.chatSessions.v1
```

存储格式:

```json
{
  "version": 1,
  "activeChatSessionId": "chat_...",
  "sessions": []
}
```

约束:

- 最多保留 50 个会话。
- 单个会话最多保留 200 条消息。
- 单条消息 `content` 最多保留 20000 个字符。超过时保存前截断，并在末尾追加 `...`。
- localStorage 读取失败、JSON 解析失败或 schema 不匹配时，不阻断页面加载，应重置为空会话列表并在控制台记录 warning。
- 每次创建会话、切换 active session、发送/接收消息、删除会话、重命名会话后都要保存。
- 不得把 API Key 或配置敏感字段写入该 storage key。

迁移策略:

- 第一版只支持 `version: 1`。
- 如果发现更高版本，忽略旧数据并重置，避免错误解析。

---

## 10. API 契约

### 10.1 复用现有聊天接口

不新增后端接口。使用已有:

```http
POST /desktop/chat
Content-Type: application/json

{
  "message": "帮我看一下今天下午应该优先处理什么？",
  "context": {
    "type": "global",
    "title": "会话 tab",
    "currentStatus": {},
    "futureTasks": []
  }
}
```

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

### 10.3 上下文数据来源

使用现有接口:

- `GET /desktop/status`
- `GET /desktop/summary`
- `GET /desktop/tasks`
- `POST /desktop/chat`
- `POST /desktop/tasks`

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

- 创建一个新的 `ChatSession`。
- `title` 初始为 `新会话`。
- `source` 为 `manual`。
- 切换为 active session。
- 输入框获得焦点。
- 保存到 localStorage。

首次用户发送消息后，如果 title 仍为 `新会话`，用用户首条消息生成标题:

- 取首条用户消息前 18 个中文字符或 36 个 ASCII 字符。
- 去掉换行。
- 为空则保持 `新会话`。

### SPEC-CHAT-TAB-004: 会话列表

左侧会话列表:

- 按 `updatedAt` 降序排列。
- active session 有明显高亮和左侧强调条。
- 每项展示 title、最后一条消息摘要、更新时间。
- 搜索框按 title 和消息内容过滤。
- 没有匹配结果时显示 `没有匹配的会话`。

会话列表 item 点击后:

- 切换 `activeChatSessionId`。
- 渲染对应消息。
- 保存 active id。

会话列表 item 悬停时显示删除按钮:
- 点击 X 按钮弹出确认对话框。
- 确认后删除会话及其所有消息，从 localStorage 移除。
- 若删除的是当前活跃会话，自动切换到列表第一个会话；若列表为空则自动创建新会话。

### SPEC-CHAT-TAB-005: 发送消息

用户在 `chat-tab-input` 输入消息后:

- 点击 `发送` 或按 `Enter` 发送。
- `Shift+Enter` 插入换行。
- 空白消息不发送。
- 发送中禁用发送按钮，防止重复提交。
- 发送前将用户消息追加到 active session。
- 创建一个 pending assistant 消息，显示 `思考中...` 或加载态。
- 调用 `POST /desktop/chat`。
- 成功后用 Agent 回复替换 pending 消息。
- 失败后 pending 消息变成 error 状态，并展示错误信息和 `重试` 操作。

发送成功后:

- 清空输入框。
- 滚动到最新消息。
- 更新 session `updatedAt`。
- 保存到 localStorage。

### SPEC-CHAT-TAB-006: 上下文构建

每次发送消息时，根据右侧开关构建 context:

```js
{
  type: "global",
  title: activeSession.title,
  currentStatus: state.chatContextToggles.currentStatus ? buildCurrentStatusContext() : null,
  futureTasks: state.chatContextToggles.futureTasks ? buildFutureTasksContext() : null,
  recentActivity: buildRecentActivityContext(),
  history: state.chatContextToggles.history ? buildShortChatHistory(activeSession) : null
}
```

要求:

- `currentStatus` 从 `state.summary.current` 或当前状态卡片数据提取。
- `futureTasks` 从 `state.tasks` 中提取未完成任务，最多 10 条。
- `recentActivity` 从 `state.summary.timeline` 中提取最近 4 条。
- `history` 只包含最近 10 条消息的 role/content，不包含 pending/error 消息。
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
   - 完整历史会话: 默认关。

### SPEC-CHAT-TAB-010: LLM 未配置降级

如果 `state.status.llm` 或配置显示 LLM 不可用:

- 输入框禁用或发送时给出明确提示。
- 中间区域显示 `配置 LLM 后可进行会话`。
- 右侧上下文仍可显示本地摘要和任务。
- `配置` tab 不受影响。

不得让页面长时间停留在 `加载中...`。

### SPEC-CHAT-TAB-011: 请求失败

聊天请求失败时:

- 当前用户消息保留。
- assistant pending 消息变为 error。
- 显示错误文案: `发送失败: <原因>`。
- 提供 `重试` 操作。重试时使用同一条用户消息和同一份 contextSnapshot。
- 不创建空 assistant 消息。
- 不清空 localStorage 中已有历史。

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
   - `chat.js` — chat session 持久化、渲染、消息发送、上下文构建
   - `events.js` — 新增消息发送、会话管理、上下文开关等事件处理
   - `agent.js` — 修改追问入口导向 `会话` tab

3. `self-analyst-app/src/main/resources/desktop-ui/styles.css`
   - 新增会话 tab 三栏布局和响应式样式。
   - 不破坏已有 Agent/Config 样式。

### 13.2 不应修改的文件

第一版不需要修改:

- `self-analyst-app/src/main/java/...`
- `self-analyst-aw/...`
- `self-analyst-desktop/src-tauri/...`
- `scripts/build-dist.ps1`

除非实现者发现现有 `POST /desktop/chat` 返回契约完全无法满足本 spec。若确需后端修改，必须先补充说明并保持向后兼容。

---

## 14. 推荐实现顺序

1. HTML 结构
   - 增加 nav tab。
   - 增加 `#tab-chat` 基础三栏 DOM。

2. Tab 切换
   - `cacheDom()` 增加 `tabChat` 和 chat tab 元素。
   - `switchTab(tab)` 支持 `chat`。
   - 点击 `会话` 后不触发配置加载。

3. 状态和持久化
   - 增加 `state.chatSessions`, `activeChatSessionId`, `chatSending`, `chatContextToggles`。
   - 实现 `loadChatSessions()`, `saveChatSessions()`, `createChatSession()`, `getActiveChatSession()`。

4. 渲染
   - 实现 `renderChatTab()`。
   - 实现左侧列表、中间消息、右侧上下文三个渲染函数。
   - 处理空状态和错误状态。

5. 发送消息
   - 实现 `sendChatTabMessage()`。
   - 使用 `api.postChat()`。
   - 处理 pending/success/error。

6. 建议任务
   - 复用 `api.createTask()`。
   - 创建成功后调用现有 `loadTasks()` 或直接刷新 `state.tasks` 后 `renderChatTab()`。

7. Agent tab 入口联动
   - 修改当前状态、时间轴、任务讨论按钮。
   - 调用 `openChatTabWithContext(context)`。

8. CSS 和响应式
   - 先实现 1200x800 可用。
   - 再处理 800x600 不重叠。

9. 验证和打包
   - 浏览器打开 `http://localhost:5700/desktop-ui/` 手动验证。
   - Maven package 确认静态资源进入 jar。

---

## 15. 详细伪代码

### 15.1 初始化

```js
function init() {
  cacheDom();
  loadChatSessions();
  bindEvents();
  loadInitialData();
}
```

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
    ensureActiveChatSession();
    renderChatTab();
    focusChatInputSoon();
  }

  if (tab === "config" && !state.config) {
    loadConfig();
  }
}
```

### 15.3 创建会话

```js
function createChatSession(opts) {
  var now = new Date().toISOString();
  var session = {
    id: "chat_" + Date.now().toString(36) + "_" + Math.random().toString(36).slice(2, 8),
    title: opts && opts.title ? opts.title : "新会话",
    createdAt: now,
    updatedAt: now,
    source: opts && opts.source ? opts.source : "manual",
    contextLabel: opts && opts.contextLabel,
    contextSnapshot: opts && opts.contextSnapshot,
    messages: []
  };
  state.chatSessions.unshift(session);
  state.activeChatSessionId = session.id;
  saveChatSessions();
  renderChatTab();
  return session;
}
```

### 15.4 从上下文打开会话 tab

```js
function openChatTabWithContext(context) {
  var session = createChatSession({
    title: context.title || "上下文追问",
    source: context.type === "task" ? "task_context" : "agent_context",
    contextLabel: context.label || context.title,
    contextSnapshot: context
  });

  session.messages.push({
    id: createId("msg"),
    role: "system",
    content: "已带入上下文：" + (context.title || context.label || "当前条目"),
    createdAt: new Date().toISOString(),
    contextSnapshot: context
  });

  saveChatSessions();
  switchTab("chat");
}
```

### 15.5 发送消息

```js
function sendChatTabMessage() {
  if (state.chatSending) return;

  var input = state.dom.chatTabInput;
  var text = input.value.trim();
  if (!text) return;

  var session = ensureActiveChatSession();
  var context = buildChatContext(session);
  var now = new Date().toISOString();

  var userMsg = {
    id: createId("msg"),
    role: "user",
    content: text,
    createdAt: now,
    status: "sent",
    contextSnapshot: context
  };

  var pendingMsg = {
    id: createId("msg"),
    role: "assistant",
    content: "思考中...",
    createdAt: now,
    status: "pending"
  };

  session.messages.push(userMsg, pendingMsg);
  updateSessionTitleFromFirstMessage(session);
  touchSession(session);
  input.value = "";
  state.chatSending = true;
  saveChatSessions();
  renderChatTab();

  api.postChat(text, context)
    .then(function (resp) {
      pendingMsg.status = "sent";
      pendingMsg.content = resp.message || resp.reply || resp.content || "Agent 未返回可显示内容";
      pendingMsg.suggestedTasks = resp.suggestedTasks || resp.suggested_tasks || resp.tasks || [];
      touchSession(session);
      state.chatSending = false;
      saveChatSessions();
      renderChatTab();
    })
    .catch(function (err) {
      pendingMsg.status = "error";
      pendingMsg.content = "发送失败: " + (err.message || "未知错误");
      pendingMsg.error = err.message || String(err);
      state.chatSending = false;
      saveChatSessions();
      renderChatTab();
    });
}
```

---

## 16. 边界条件

### 16.1 无 localStorage

如果 localStorage 不可用:

- 页面仍可会话，但只保存在内存。
- 控制台 warning。
- 右侧或会话偏好区域可显示 `当前环境无法持久保存会话`。

### 16.2 LLM 调用慢

如果请求超过 20 秒:

- UI 仍保持 pending，不允许重复提交同一消息。
- 可提供 `停止等待` 或 `重试`。第一版可不实现取消请求，但必须能继续使用其他 tab。

### 16.3 会话过多

保存前裁剪:

- 按 `updatedAt` 降序保留前 50 个。
- 每个会话保留最近 200 条消息。

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
- `app.js` 的 `switchTab` 支持 `chat`。
- `app.js` 包含 `selfAnalyst.chatSessions.v1`。
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
4. 输入消息，`Enter` 发送，Network 出现 `POST /desktop/chat`。
5. 发送中按钮禁用。
6. 成功后消息出现在会话中。
7. 刷新页面后，会话仍存在。
8. 搜索会话能过滤列表。
9. 建议任务点击后调用 `POST /desktop/tasks`。
10. 点击配置 tab 再回会话 tab，active session 保持。

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
- `会话` tab 能创建、切换、搜索本地会话。
- 消息发送使用现有 `POST /desktop/chat`。
- 会话历史刷新页面后仍存在。
- Agent 回复中的建议任务可由用户点击创建为待办。
- Agent tab 的追问入口能跳转到 `会话` tab 并带入上下文。
- LLM 不可用时界面清晰提示，不出现永久加载。
- 800x600 到宽屏范围内无关键内容重叠。
- Maven package 通过。
- 不新增无必要后端接口。
- 不破坏现有 Agent tab、配置 tab、Web 仪表盘按钮。

---

## 19. 规格追溯矩阵

| 规格 ID | 文件/函数 | 验收方式 |
|---------|-----------|----------|
| SPEC-CHAT-TAB-001 | `index.html`, `switchTab` | 点击 tab 手动测试 |
| SPEC-CHAT-TAB-002 | `renderChatTab` | 首次进入会话页 |
| SPEC-CHAT-TAB-003 | `createChatSession` | 新建会话测试 |
| SPEC-CHAT-TAB-004 | `renderChatSessionList` | 切换和搜索测试 |
| SPEC-CHAT-TAB-005 | `sendChatTabMessage` | Network + UI 状态 |
| SPEC-CHAT-TAB-006 | `buildChatContext` | 请求 payload 检查 |
| SPEC-CHAT-TAB-007 | `openChatTabWithContext` | Agent tab 追问测试 |
| SPEC-CHAT-TAB-008 | `renderSuggestedTasks`, `api.createTask` | 建议转任务测试 |
| SPEC-CHAT-TAB-009 | `renderChatContextPanel` | 右侧上下文检查 |
| SPEC-CHAT-TAB-010 | LLM 状态判断 | LLM 未配置测试 |
| SPEC-CHAT-TAB-011 | error handling | 模拟 500/断网 |
| SPEC-CHAT-TAB-012 | event binding | 键盘交互测试 |

---

## 20. 实现注意事项

- 当前前端不是 React/Vue 项目，是原生 HTML/CSS/JS。不要引入新框架。
- 继续使用 ES5/兼容性较好的写法，保持现有 `app.js` 风格。
- 手写 HTML 字符串时必须使用现有 `escHtml()` 处理用户/Agent 文本。
- 不要把 LLM 返回内容直接赋给 `innerHTML`。
- 不要把 API Key、配置敏感值、完整用户配置写入 chat localStorage。
- 不要修改用户已有任务数据结构，建议任务转待办时只使用后端已支持字段。
- 新增 CSS 不得影响 `.chat-drawer` 除非明确迁移旧抽屉。
- 如果实现者决定删除旧 chat drawer，必须同时删除 HTML、CSS、JS 引用并完成回归测试；第一版不要求删除。

