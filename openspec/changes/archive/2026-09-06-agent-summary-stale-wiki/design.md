## Context

当前 `GET /desktop/summary` 在一次同步请求里查询约 8 个时间窗的 window/AFK 事件，并对当前状态、最多 4 条时间轴和行为建议调用 LLM。前端 `loadAll` 把 Agent 页初始 HTML 占位留到该请求结束（最长 30 秒），`state.summary` 只存在内存里。后台 Wiki worker 已把已结束 HOUR/HALF_DAY/DAY/WEEK/BIWEEK/MONTH 摘要写入 `llm-wiki.db`，但桌面时间轴并不读取它。动机见 `proposal.md`；行为契约见 `specs/`。

## Goals / Non-Goals

**Goals:**

- 让再次打开 Agent 页时先看到上次成功内容，而不是加载占位。
- 已结束时段走 Wiki，现场查询和 LLM 只留在当前窗。
- 保持 `current` / `timeline` / `behaviorAdvice` 响应兼容，并守住标题事实隐私边界。

**Non-Goals:**

- 不改 Wiki worker 的发现、生成、补算或语义索引。
- 不做流式 SSE、独立 Wiki 浏览页或跨设备同步。
- 不把前端 localStorage 当作摘要权威（会话列表已明确以后端为源）。
- 不调整 `desktop.summary.maxTimelineLlm` 的默认数值，只收窄其作用范围。

## Decisions

### 1. 服务端快照是权威，前端只做展示缓存

在 `{memory.dir}` 下用原子写保存最近一次成功摘要 JSON（含 `current`、`timeline`、`behaviorAdvice`、组装时间和当前窗事实指纹）。`GET /desktop/summary` 先读快照再叠加当前窗增量。前端仍把上次响应放在内存里避免刷新闪烁，但不把 localStorage 当权威，以免和会话数据策略冲突。

备选：只做前端缓存。否决原因：Tauri/浏览器重载后仍会先打满加载占位，且两个入口不能共享。

### 2. 一个兼容接口，语义改为「快照 + 当前窗」

继续使用 `GET /desktop/summary`。处理顺序：

1. 读取快照（可缺省）。
2. 用 Wiki 覆盖或填充已结束条目。
3. 只查询当前窗本地事实（`current` 约 2 小时，`today` 当日）。
4. 当前窗 LLM 仅在无快照、指纹变化或超过新鲜度阈值时调用。
5. 建议按同样失效条件复用或最多新生成一条。
6. 原子写回快照并返回。

首包不得为补齐 PENDING Wiki 或已结束时段 LLM 而阻塞。无快照时也先用 Wiki + 当前窗本地事实返回；当前窗 LLM 失败则 `localOnly`。

备选：拆成 snapshot GET + refresh POST。否决原因：会迫使旧 UI 双请求，收益不足以抵消兼容成本。

### 3. 当前窗与 Wiki 层级映射

| 时间轴 key | 闭合规则 | 摘要来源 |
|---|---|---|
| `current` | 未闭合 | 现场本地事实；按阈值可选 LLM |
| `today` | 未闭合 | 现场本地事实；按阈值可选 LLM |
| `morning` | 本地时间 ≥ 12:00 则闭合 | HALF_DAY；缺失则本地事实且不 LLM |
| `yesterday` / `dayBefore` | 闭合 | DAY |
| `thisWeek` | 周未结束则拼装 | WEEK，否则已完成 DAY + `today` |
| `lastTwoWeeks` | 同上 | BIWEEK，否则 WEEK/DAY + `today` |
| `thisMonth` | 同上 | MONTH，否则 WEEK/DAY + `today` |

Wiki 查询走既有 store 只读接口，不经 Agent 工具循环。ActivityWatch wiki summary bucket 只作辅助投影，不以它覆盖 `llm-wiki.db`。

### 4. 当前窗 LLM 与建议失效

对当前窗本地事实计算稳定指纹（排序后的 topApps、活跃/AFK 时间桶、切换次数桶）。快照当前窗超过 15 分钟，或指纹变化，才允许现场 LLM；次数仍受 `desktop.summary.maxTimelineLlm` 约束，且只数当前窗。建议使用同一失效条件。预算 block 时当前窗保持 `localOnly`，已结束 Wiki 仍可读。

备选：每次请求都 LLM 当前窗。否决原因：30 秒轮询会持续烧 token，而当前窗文案不必秒级变化。

### 5. 前端先渲染、后修补

`loadAll` 在请求返回前，只要拿到快照响应或内存中的上次 summary，就渲染建议和时间轴。有可展示内容时禁止把卡片重置为初始占位。定时刷新沿用同一 GET，但成功前保留旧 DOM；切 tab 不重拉。无任何快照时才保留「分析行为数据中… / 加载中…」。

## Risks / Trade-offs

- [Wiki 尚未补齐历史] → 已结束时段先显示本地事实；不现场 LLM，避免重新引入今日的等待。
- [快照与 Wiki 短暂不一致] → 以 Wiki 覆盖已结束条目；快照只保证「先能看见」。
- [今天跨日或时区变化] → 按系统默认时区重算开闭；跨日后旧 `today` 降为昨天并改走 Wiki/本地事实。
- [快照含过期建议] → 用当前窗指纹失效，而不是固定墙钟单独刷新建议。
- [首次安装仍会等待] → 无快照时首包已不含历史 LLM；等待上限从「多段 LLM」降为「当前窗本地查询 ± 最多一次增强」。

## Migration Plan

- 无快照时的行为是更快的兼容路径，不需要数据迁移。
- 旧客户端忽略新元数据即可。
- 回滚：恢复同步全量生成后，快照文件可忽略；Wiki 数据不受影响。

## Open Questions

- 当前窗新鲜度 15 分钟是否需要做成可配置项：不影响本轮规格与任务拆分，可在实现时用常量，后续再加配置。
