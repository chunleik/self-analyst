## Why

Agent 页每次打开都会从空白占位开始，同步重算全部时间窗事实并再次调用 LLM，导致「分析行为数据中… / 加载中…」等待过长。后台 Wiki 已经持久化了已结束时段的摘要，但时间轴没有复用，重复打开和 30 秒轮询都在浪费时间和 token。

## What Changes

- **新行为**：打开 Agent 页时，若存在上次成功摘要快照，SHALL 立即展示该快照，再在后台刷新未闭合时段；不得在有可用快照时先清空为加载占位。
- **新行为**：后端为 `GET /desktop/summary` 维护上次成功快照；已结束时段优先读取 Wiki `SUMMARIZED` 条目，不再现场对整段历史做 LLM 增强。
- **行为修改**：现场计算与可选 LLM 增强仅针对未闭合的「当前窗」（最近约 2 小时，以及仍在进行的「今天」本地事实）；周 / 双周 / 月等跨度由已完成 Wiki 子段加当前窗拼装。
- **行为修改**：行为建议在快照仍有效时复用，不因页面重开而强制重新生成。
- **行为修改**：定时刷新只更新未闭合时段，不得对已结束时段再次调用 LLM。
- **非破坏**：`GET /desktop/summary` 对外字段保持 `current` / `timeline` / `behaviorAdvice`；客户端可忽略新增的来源与新鲜度元数据。

## Capabilities

### New Capabilities

- `desktop-summary`: Agent 页当前状态与时间轴的组装、快照先出、已结束时段 Wiki 复用，以及仅对当前窗现场计算。

### Modified Capabilities

- `behavior-advice`: 建议生成不再绑定「每次 summary 请求都必须重算」；快照有效时可复用上一条建议。
- `llm-wiki`: 桌面时间轴对已结束时段 SHALL 以 `llm-wiki.db` 中的 `SUMMARIZED` 条目为摘要权威，而不是再次现场生成。
- `llm-budget`: `desktop.summary.maxTimelineLlm` 只约束当前窗的现场 LLM 增强次数，已结束 Wiki 条目不计入该上限。
- `content-event-persistence`: 明确桌面 summary/timeline 可消费由允许标题事实派生的 Wiki 摘要，仍不得读取或拼接无障碍正文。

## Impact

- 后端：`DesktopAgentController.getSummary`、`SummaryService`、摘要快照持久化，以及从 `WikiStore` 读取已结束时段；不改变 Wiki worker 的生成与补算流程。
- 前端：`loadAll` / `startAutoRefresh` / `renderTimeline` / `renderBehaviorAdvice`；有快照时跳过初始加载占位。
- API：`GET /desktop/summary` 语义从「每次同步全量生成」变为「快照 + 当前窗增量」；响应形状兼容。
- 预算：减少重复 SUMMARY 调用；`maxTimelineLlm` 含义收窄为当前窗。
- 隐私：快照与时间轴仍不得包含 OCR/UIA 正文、API key 或完整配置。
