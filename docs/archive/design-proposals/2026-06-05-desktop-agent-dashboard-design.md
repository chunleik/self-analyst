# SelfAnalyst Desktop Agent Dashboard Redesign (ARCHIVED)

> 本设计已实现并正式化为正式 spec。请参见：
> - [desktop-shell](../../../openspec/specs/desktop-shell/spec.md) — Tauri 桌面壳主规格
> - [desktop-chat](../../../openspec/specs/desktop-chat/spec.md) — 会话 tab 主规格
>
> 本文只保留 2026-06-05 时的设计背景，其中固定端口、`config.properties`、单文件前端等描述均非
> 当前实现，不应作为操作或开发依据。

日期: 2026-06-05
状态: Design spec
方向: 方向2 - 左侧回顾时间轴 + 右侧固定未来任务栏

## 1. 背景

当前 `self-analyst-desktop` 是一个 Tauri 桌面壳，主要职责是启动 Java 后端、创建托盘、打开 WebView，并默认加载 `http://localhost:5700/desktop-ui/` 的轻量桌面入口。完整 Web UI 仍保留在 `http://localhost:5700/`，用于承载数据浏览、图表、bucket、query、日志等能力。

桌面端继续承载完整 Web UI 会导致两个问题:

- 桌面端信息密度过高，打开后缺少清晰的下一步行动。
- 桌面端和 Web 端职责重叠，用户不知道应在桌面端看什么、在浏览器里看什么。

新的桌面端定位是轻量工作入口: 启动和管理本地服务，展示 Agent 对用户当前状态、近期行为和未来任务的判断，并允许用户直接配置系统。

## 2. 设计结论

桌面端改成两个 tab:

- `Agent`: 默认显示主动总结面板。页面左侧是竖向时间轴，展示当前、今天、上午、昨天、前天、本周、最近两周、本月的行为总结。页面右侧是固定未来任务栏，展示用户输入和 Agent 建议转化来的待办事项。聊天作为追问和编辑入口，不作为默认空白首页。
- `配置`: 在桌面端直接编辑并保存运行配置，包括 LLM、ActivityWatch、采集开关、音频、数据目录、端口和桌面行为。完整数据仪表盘保留为按钮跳转到浏览器 Web UI。

桌面端不再 iframe 完整 AW Web UI。Tauri 加载本地静态桌面前端，前端通过 `localhost:5700` 调用 Java 后端新增的桌面 API。

## 3. 目标

- 打开桌面端后，用户第一眼能看到当前在干什么、今天在干什么、近期在干什么，以及接下来该做什么。
- 桌面端默认页由 Agent 主动给出总结和建议，而不是让用户面对一个空输入框。
- 保留会话能力: 用户可以点击总结、建议或任务进入对话，向 Agent 追问原因、要求细化建议、创建或修改待办事项。
- 配置页支持界面内保存，不要求用户手动编辑 `application.properties` 或环境变量。
- Web 端继续承担完整数据展示。桌面端只提供进入 Web 仪表盘的入口。
- 当 LLM API key 未配置或 LLM 调用失败时，桌面端仍能显示基于本地数据的基础时间轴和配置页。

## 4. 非目标

- 不在桌面端重做完整 ActivityWatch 仪表盘、日历、bucket 管理、AQL 查询和日志查看。
- 不在第一版实现复杂项目管理能力，例如多人协作、依赖关系、甘特图、循环任务。
- 不在第一版引入云同步。所有配置、任务和记忆默认保存在本机。
- 不把配置保存到打包在 jar 内部的 `application.properties`。桌面端应写入用户级配置文件。

## 5. 产品结构

### 5.1 顶部导航

窗口顶部保留简单导航:

- 左侧: `SelfAnalyst`
- Tab 1: `Agent`
- Tab 2: `配置`
- 右侧可选状态: 后端运行、采集中、LLM 可用、Web 仪表盘按钮

主窗口建议尺寸保持现有规格:

- 初始尺寸: 1200 x 800
- 最小尺寸: 800 x 600
- 宽屏时采用左右布局
- 窄屏时未来任务栏折叠到时间轴下方

### 5.2 Agent tab 总体布局

采用方向2:

```text
┌─────────────────────────────────────────────────────────────┐
│ SelfAnalyst    Agent    配置                         状态/按钮 │
├─────────────────────────────────────────────────────────────┤
│ ┌───────────────────────────────┐ ┌───────────────────────┐ │
│ │ 当前状态卡片                    │ │ 未来任务               │ │
│ │ 正在做什么 + 建议               │ │ 固定展示               │ │
│ └───────────────────────────────┘ │ 任务列表               │ │
│ ┌───────────────────────────────┐ │                       │ │
│ │ 竖向时间轴                      │ │                       │ │
│ │ 今天                            │ │                       │ │
│ │ 上午/下午条件分段                │ │                       │ │
│ │ 昨天                            │ │                       │ │
│ │ 前天                            │ │                       │ │
│ │ 本周                            │ │                       │ │
│ │ 最近两周                        │ │                       │ │
│ │ 本月                            │ │                       │ │
│ └───────────────────────────────┘ └───────────────────────┘ │
└─────────────────────────────────────────────────────────────┘
```

左侧回顾区可滚动。右侧未来任务栏固定在视口内，用户滚动历史总结时仍能看到下一步任务。

## 6. Agent tab 详细规格

### 6.1 当前状态卡片

当前状态卡片始终位于左侧顶部，不进入滚动轴内部。

内容:

- 标签: `当前`
- 主标题: 当前正在干什么，例如 `正在写代码，集中度较高`
- 证据摘要: 最近活跃窗口、内容片段、连续工作时长、切换频率
- Agent 建议: 一条具体建议，例如 `完成当前函数后休息 5 分钟，再回到测试验证`
- 操作:
  - `追问`: 打开聊天输入框，并带入当前状态上下文
  - `记录为任务`: 将建议转成待办草稿
  - `忽略`: 本次不再强调该建议

状态识别应优先使用本地事件聚合，不依赖 LLM 才能显示。LLM 可用于润色标题、判断工作模式和生成建议。

### 6.2 时间轴区间

时间轴按固定顺序展示:

1. `今天`: 当天 00:00 到当前时间。
2. `上午`: 仅当本地时间大于等于 12:00 时展示，范围为当天 00:00 到 12:00。
3. `下午`: 可选分段。若当前时间大于等于 18:00，可展示当天 12:00 到 18:00。第一版可不单独展示下午，因为 `当前` 和 `今天` 已覆盖。
4. `昨天`: 前一自然日 00:00 到 24:00。
5. `前天`: 前二自然日 00:00 到 24:00。
6. `本周`: 本周一 00:00 到当前时间，使用用户本地时区。
7. `最近两周`: 滚动 14 天窗口，到当前时间为止。
8. `本月`: 当月 1 日 00:00 到当前时间。

所有时间计算使用系统本地时区。当前项目运行环境主要是 Windows 桌面端，但时间逻辑不应绑定 Windows API。

### 6.3 时间轴条目结构

每个时间轴条目包含:

- `label`: 显示标签，例如 `今天`
- `periodStart`: ISO 时间
- `periodEnd`: ISO 时间
- `headline`: 一句话总结
- `evidence`: 2 到 4 条证据，例如主要应用、窗口标题主题、连续时段、内容关键词
- `insight`: Agent 判断，例如工作是否集中、是否频繁切换、是否偏离目标
- `suggestion`: 可执行建议，必须具体
- `confidence`: `low | medium | high`
- `coverage`: 数据覆盖说明，例如 `窗口数据可用，内容识别部分缺失`

UI 显示时默认展示 `headline`、一段短摘要和 1 到 2 个标签。点击条目后展开证据、建议和聊天入口。

### 6.4 聊天入口

Agent tab 默认不是聊天窗口，但每个核心元素都可以进入对话:

- 点击当前状态卡片的 `追问`
- 点击时间轴条目的 `追问`
- 点击未来任务的 `讨论`
- 点击页面底部或右下角的固定输入入口

打开方式:

- 第一版采用右下或底部内联聊天抽屉，不跳转页面。
- 聊天抽屉打开时携带上下文，例如当前选择的是 `昨天` 条目，消息上下文中包含该条目的 period、headline 和 evidence。
- 用户发送消息后，Agent 可以回答、细化建议、创建待办草稿、修改待办草稿。

聊天历史第一版保存在当前桌面会话内。长期记忆仍由现有 `MemoryStore` 管理。

### 6.5 空数据与降级

如果没有足够采集数据:

- 当前状态显示 `暂无足够数据`
- 时间轴条目显示数据缺口和建议，例如 `保持采集服务运行一段时间后再查看总结`
- 未来任务仍可正常使用
- 配置页仍可正常使用

如果 LLM 不可用:

- 显示本地聚合摘要，例如主要应用、窗口标题、使用时长
- Agent 建议区域显示 `配置 LLM 后可生成建议`
- 聊天入口禁用或提示配置 LLM

## 7. 未来任务规格

### 7.1 任务来源

未来任务有两类来源:

- 用户手动输入: 用户在右侧任务栏输入待办事项。
- Agent 建议转化: 用户点击建议上的 `记录为任务` 后创建，不能由 Agent 静默创建。

第一版不允许 Agent 未经确认直接写入任务列表。这样可以避免错误建议污染用户待办。

### 7.2 任务字段

任务数据结构:

```json
{
  "id": "task_01hx...",
  "title": "补桌面 Agent API",
  "notes": "为桌面端提供 summary、chat、config、tasks 接口",
  "status": "open",
  "priority": "high",
  "dueAt": "2026-06-05T18:00:00+08:00",
  "source": "user",
  "createdAt": "2026-06-05T20:00:00+08:00",
  "updatedAt": "2026-06-05T20:00:00+08:00",
  "completedAt": null
}
```

字段约束:

- `title` 必填，1 到 120 个字符。
- `status`: `open | completed | archived`
- `priority`: `low | medium | high`
- `source`: `user | agent_suggestion`
- `dueAt` 可为空。为空时按创建时间和优先级排序。

### 7.3 任务展示

右侧任务栏展示:

- `今天到期`
- `即将到来`
- `无截止日期`
- 已过期任务用明显但克制的状态标识

排序规则:

1. 未完成任务优先于已完成任务。
2. 有截止时间的任务优先。
3. 截止时间早的优先。
4. 同一截止时间内，高优先级优先。
5. 最后按更新时间倒序。

操作:

- 新建
- 编辑标题、备注、截止时间、优先级
- 标记完成
- 归档
- 从任务进入聊天讨论

### 7.4 任务存储

任务保存在用户数据目录，建议路径:

```text
<memory.dir>/tasks.json
```

默认情况下 `memory.dir` 为:

```text
%USERPROFILE%/.self-analyst
```

保存必须使用原子写入:

1. 写入临时文件。
2. flush。
3. rename 覆盖正式文件。

如果保存失败，UI 必须保留用户输入并显示错误，不得丢弃未保存任务。

## 8. 配置 tab 规格

### 8.1 配置页分组

配置页分为以下区块:

- LLM
  - API key
  - Base URL
  - Model
  - Temperature
  - 测试连接
- ActivityWatch
  - AW 模式: embedded 或 external
  - 端口
  - 数据目录
  - 打开 Web 仪表盘
- 采集
  - 窗口采集开关
  - AFK 采集开关
  - 内容识别开关
  - OCR 引擎: auto、paddle、tesseract
- 音频
  - 音频采集开关
  - Whisper 路径或工具目录
- Agent
  - 总结刷新频率
  - 是否允许 Agent 建议任务
  - 是否缓存 LLM 总结
- 桌面行为
  - 关闭窗口时隐藏到托盘
  - 启动时自动打开窗口
  - 启动时自动打开后端

### 8.2 用户配置文件

新增用户级配置文件:

```text
%USERPROFILE%/.self-analyst/config.properties
```

配置加载优先级:

1. JVM system properties
2. 环境变量
3. 用户级 `config.properties`
4. jar 内置 `application.properties`
5. 默认值

如果某项配置被环境变量或 JVM 参数覆盖，UI 仍显示有效值，但保存时应提示 `当前值由环境变量覆盖，界面保存不会改变运行中有效值`。这类字段可以允许编辑并保存到用户配置，也可以标记为 overridden。第一版建议标记为 overridden，避免用户误解。

### 8.3 保存行为

配置页有统一 `保存` 按钮，也支持单区块保存。

保存流程:

1. 前端读取当前配置和版本号。
2. 用户修改后提交 `PUT /desktop/config`。
3. 后端校验字段。
4. 后端写入用户级配置文件。
5. 后端返回新的有效配置和 `restartRequired` 列表。
6. UI 显示保存结果。

需要重启后端才生效的配置:

- AW 端口
- AW 数据目录
- embedded/external 模式
- 工具目录
- Java 进程级开关

可立即生效的配置:

- LLM base URL
- LLM model
- temperature
- 总结刷新频率
- 桌面 UI 行为

API key 保存后不应在 UI 明文回显。后端返回 masked 值，例如 `sk-...abcd`。

## 9. 后端 API 规格

新增桌面 API 前缀:

```text
/desktop
```

### 9.1 状态

```http
GET /desktop/status
```

返回:

```json
{
  "backend": "running",
  "aw": {
    "mode": "embedded",
    "port": 5700,
    "webUrl": "http://localhost:5700/"
  },
  "collectors": {
    "window": "running",
    "afk": "running",
    "content": "degraded",
    "audio": "disabled"
  },
  "llm": {
    "configured": true,
    "available": true,
    "model": "deepseek-v4-pro"
  }
}
```

### 9.2 Agent 总结

```http
GET /desktop/summary
```

返回:

```json
{
  "generatedAt": "2026-06-05T20:30:00+08:00",
  "current": {
    "headline": "正在写代码，集中度较高",
    "evidence": ["IDE 连续活跃 46 分钟", "浏览器和终端切换较少"],
    "suggestion": "完成当前函数后休息 5 分钟，再回到测试验证",
    "confidence": "medium"
  },
  "timeline": [
    {
      "key": "today",
      "label": "今天",
      "periodStart": "2026-06-05T00:00:00+08:00",
      "periodEnd": "2026-06-05T20:30:00+08:00",
      "headline": "主要在推进桌面端 Agent 入口",
      "evidence": ["IDE 活跃占比最高", "规格文档和终端频繁出现"],
      "insight": "工作重心从采集能力转向产品体验",
      "suggestion": "先完成桌面 API，再实现前端时间轴",
      "confidence": "medium",
      "coverage": "window data available, content data partial"
    }
  ],
  "degraded": false
}
```

### 9.3 聊天

```http
POST /desktop/chat
```

请求:

```json
{
  "message": "为什么你判断我今天主要在做桌面端？",
  "context": {
    "type": "timeline_entry",
    "key": "today"
  }
}
```

返回:

```json
{
  "message": "因为今天 IDE、规格文档和桌面端源码窗口占比最高...",
  "suggestedTasks": [
    {
      "title": "补桌面 Agent API",
      "priority": "high",
      "dueAt": "2026-06-06T18:00:00+08:00"
    }
  ]
}
```

第一版可以不做流式返回。后续如果 AgentScope stream 能稳定暴露到 HTTP，再增加 SSE。

### 9.4 任务

```http
GET /desktop/tasks
POST /desktop/tasks
PUT /desktop/tasks/{id}
POST /desktop/tasks/{id}/complete
POST /desktop/tasks/{id}/archive
DELETE /desktop/tasks/{id}
```

`DELETE` 仅用于误创建后的硬删除。普通完成流使用 `complete` 或 `archive`。

### 9.5 配置

```http
GET /desktop/config
PUT /desktop/config
POST /desktop/config/test-llm
```

`GET /desktop/config` 返回字段应包含:

- `effectiveValue`: 当前运行中的有效值
- `savedValue`: 用户配置文件中的值
- `source`: `system_property | env | user_config | bundled | default`
- `overridden`: 是否被更高优先级来源覆盖
- `restartRequiredOnChange`: 是否需要重启后端

## 10. 后端组件设计

新增组件建议放在 `self-analyst-app`，因为它同时需要访问 `SelfAnalystAgent`、`Config`、`MemoryStore` 和 embedded AW 运行状态。

建议组件:

- `DesktopServer`: 注册 `/desktop/*` API，并复用或持有 `AwServer`。
- `SummaryService`: 汇总 AW 事件，生成当前状态和时间轴条目。
- `SummaryPromptService`: 在 LLM 可用时将本地聚合结果转成更自然的总结和建议。
- `TaskStore`: 读写 `tasks.json`，提供任务 CRUD。
- `UserConfigStore`: 读写用户级 `config.properties`。
- `DesktopConfigController`: 配置读取、校验、保存和 LLM 测试。
- `DesktopAgentController`: summary 和 chat。
- `DesktopTaskController`: 任务接口。

`self-analyst-aw` 继续保持 ActivityWatch 兼容 API 和 Web UI 资源服务，不承担 Agent 产品语义。

## 11. 前端组件设计

Tauri 前端建议从当前单一 iframe 页面变为本地静态应用:

```text
self-analyst-desktop/src/
├── index.html
├── app.js
└── styles.css
```

第一版可以使用原生 HTML/CSS/JS，不必引入 React/Vue。原因:

- 两个 tab 的交互有限。
- 项目当前 desktop 前端几乎为空。
- 避免为了轻桌面端引入新的打包复杂度。

组件划分:

- `AppShell`: 顶部导航和状态栏
- `AgentTab`: Agent 默认页
- `CurrentStatusCard`: 当前状态
- `Timeline`: 左侧时间轴
- `TimelineEntry`: 单个区间条目
- `FutureTasksPanel`: 右侧任务栏
- `TaskEditor`: 新建和编辑任务
- `ChatDrawer`: 追问聊天抽屉
- `ConfigTab`: 配置页
- `ConfigSection`: 配置分组

如果后续交互明显增长，再迁移到 TypeScript + Vite。第一版保持最少依赖。

## 12. 数据流

启动:

1. 用户启动 `SelfAnalyst.exe`。
2. Tauri 启动 Java 后端。
3. Tauri 加载本地 `index.html`。
4. 前端调用 `/desktop/status`。
5. 前端调用 `/desktop/summary` 和 `/desktop/tasks`。

查看总结:

1. `SummaryService` 从 AW event store 查询窗口、AFK、内容、音频数据。
2. 本地聚合生成结构化事实。
3. 如果 LLM 可用，调用 `SelfAnalystAgent` 或专用 summary prompt 生成自然语言总结。
4. 返回 current 和 timeline。
5. 前端渲染时间轴。

创建任务:

1. 用户在任务栏输入标题。
2. 前端 `POST /desktop/tasks`。
3. `TaskStore` 原子写入 `tasks.json`。
4. 前端刷新任务栏。

保存配置:

1. 用户修改配置。
2. 前端 `PUT /desktop/config`。
3. 后端校验并写入用户级配置。
4. 后端返回有效值和是否需要重启。
5. UI 提示保存结果。

聊天:

1. 用户从当前状态、时间轴或任务打开聊天。
2. 前端提交消息和上下文。
3. 后端调用 Agent。
4. Agent 返回回答和可选任务建议。
5. 用户确认后才写入任务列表。

## 13. 总结生成策略

第一版应使用两层生成策略:

### 13.1 本地事实层

必须可在 LLM 不可用时工作。

输入:

- 窗口事件
- AFK 事件
- 内容识别事件
- 音频转录事件，如果启用
- 用户目标和记忆摘要，如果可用

输出:

- 每个时间段的主要应用
- 主要窗口标题主题
- 活跃时长
- AFK 时长
- 切换频率
- 内容关键词
- 数据缺口

### 13.2 Agent 解释层

LLM 可用时，将本地事实层转换成:

- `headline`
- `insight`
- `suggestion`
- `confidence`

约束:

- 不能编造没有证据的行为。
- 每条建议必须具体，可执行。
- 如果数据不足，必须明确说数据不足。
- 用户目标优先。如果 MemoryStore 里有目标，摘要应关联目标。

### 13.3 缓存

为了避免每次打开桌面端都触发 LLM:

- `current` 可以 1 到 5 分钟刷新一次。
- 历史区间按 period 缓存。昨天、前天、本周、最近两周、本月可以缓存到 `summary-cache.json`。
- 用户点击刷新时可以强制重新生成。

缓存路径:

```text
<memory.dir>/summary-cache.json
```

## 14. 错误处理

### 14.1 后端不可用

显示:

```text
本地服务未就绪，正在重试
```

操作:

- 重试
- 查看启动日志
- 退出

### 14.2 LLM 未配置

Agent tab:

- 显示本地事实层摘要。
- 聊天入口提示配置 LLM。
- 提供跳转到配置页的按钮。

配置 tab:

- API key 字段显示未配置。
- `测试连接` 禁用或提示先填写 API key。

### 14.3 配置保存失败

UI 必须:

- 保留用户输入。
- 显示失败原因。
- 不更新“已保存”状态。

常见失败:

- 路径无权限
- 端口不是有效数字
- API URL 格式错误
- 配置文件被其他进程占用

### 14.4 任务保存失败

UI 必须:

- 保留待办草稿。
- 提示用户重试。
- 不静默丢失任务。

### 14.5 数据缺失

时间轴条目应展示数据覆盖情况，不要假装有完整观察。

## 15. 隐私与安全

- API key 不明文回显。
- API key 写入用户级配置文件时，第一版可明文保存，但文件路径必须在用户目录下。后续可接入系统凭据库。
- 桌面 API 仅监听 localhost。
- Tauri WebView 不允许导航到任意外部 URL。
- 打开 Web 仪表盘使用系统浏览器访问 `http://localhost:<aw.port>/`。
- Agent 不应在未经用户确认时创建待办或修改配置。

## 16. 与现有系统的关系

保留:

- `self-analyst-aw` 的 AW 兼容 API。
- `self-analyst-aw/src/main/resources/webui/` 完整 Web UI。
- Tauri 托盘和 Java 后端生命周期管理。
- `SelfAnalystAgent` 的长期记忆和聊天能力。

修改:

- `self-analyst-desktop/src/index.html` 不再 iframe 完整 Web UI。
- Java 后端新增桌面 API。
- Config 增加用户级配置文件保存和加载。
- 新增任务存储。
- 新增 summary 服务。

## 17. 验收标准

### 17.1 Agent tab

- 打开桌面端默认进入 Agent tab。
- 页面显示当前状态卡片。
- 页面显示左侧竖向时间轴。
- 下午打开时，时间轴包含 `上午` 条目。
- 时间轴包含 `今天`、`昨天`、`前天`、`本周`、`最近两周`、`本月`。
- 右侧固定未来任务栏可见。
- 用户可以新建、编辑、完成和归档任务。
- 点击总结或任务可以打开聊天输入框。
- LLM 未配置时，页面仍显示本地数据摘要并提示配置 LLM。

### 17.2 配置 tab

- 用户可以在界面编辑 LLM、AW、采集、音频、Agent 和桌面配置。
- 点击保存后，配置写入用户级配置文件。
- 保存后 UI 显示有效值和是否需要重启。
- API key 不明文回显。
- 被环境变量覆盖的字段显示 overridden 状态。
- `打开 Web 仪表盘` 按钮打开完整 Web UI。

### 17.3 后端

- `/desktop/status` 返回运行状态。
- `/desktop/summary` 返回 current 和 timeline。
- `/desktop/tasks` 支持任务 CRUD。
- `/desktop/config` 支持读取和保存配置。
- 配置和任务保存失败时返回明确错误，不丢数据。

### 17.4 桌面壳

- 关闭窗口时隐藏到托盘。
- 托盘菜单支持显示窗口、Web版桌面、Web仪表盘、关于、退出。
- 重复启动桌面端时提示 `SelfAnalyst 已在运行`，不新增托盘图标或后端进程。
- 退出时 Java 后端被终止。
- 完整 Web UI 不再作为桌面默认内容加载。

## 18. 测试策略

单元测试:

- `UserConfigStore` 加载优先级和保存。
- `TaskStore` CRUD、排序、原子写入。
- `SummaryService` 时间段切分。
- 下午场景下 `上午` 条目出现。
- LLM 不可用时 summary 降级。

集成测试:

- `/desktop/config` GET/PUT。
- `/desktop/tasks` CRUD。
- `/desktop/summary` 在空数据、有窗口数据、有内容数据三种情况下返回有效结构。

手动测试:

- 双击 exe 打开 Agent tab。
- 新建待办，重启后仍存在。
- 修改模型配置，保存后刷新仍存在。
- 设置端口后提示需要重启。
- API key 未配置时，聊天不可用但页面不崩溃。
- 点击打开 Web 仪表盘，浏览器打开完整 Web UI。
- 桌面端运行时再次双击 exe，只出现已启动提示，不增加托盘图标。

## 19. 迁移策略

第一阶段:

- 保持现有 AW Web UI 和 API 不变。
- 新增桌面 API，不破坏现有 CLI 和 Web。
- 新增用户级配置文件读取，保持现有环境变量兼容。

第二阶段:

- Tauri 加载新桌面前端。
- 托盘 `Web仪表盘` 继续打开完整 Web UI，`Web版桌面` 打开轻量桌面页。

第三阶段:

- 增加 LLM summary 缓存和任务建议转化。
- 根据用户反馈调整时间轴条目密度和任务栏字段。

## 20. 实现边界

本设计适合作为一个实现计划，不需要拆成多个独立 spec。原因:

- UI 只有两个 tab。
- 后端新增 API 都围绕桌面端默认体验。
- 任务、配置、summary 之间有关联，但可以按模块顺序实现。

推荐实现顺序:

1. 用户级配置文件和配置 API。
2. 任务存储和任务 API。
3. SummaryService 本地事实层和 summary API。
4. 桌面前端两 tab。
5. Agent 聊天抽屉和 LLM summary 增强。

## 21. 设计自检

- 没有依赖完整 Web UI 作为桌面默认页。
- 时间轴区间定义明确。
- 未来任务是第一版范围内的一等能力。
- 配置保存位置明确，不写入 jar 内文件。
- LLM 失败和未配置都有降级路径。
- Agent 不会未经确认静默创建任务。
