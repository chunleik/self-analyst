## Purpose

定义 Agent 页当前状态与时间轴如何先展示上次成功快照、复用已结束时段的 Wiki 摘要，并只对未闭合当前窗做现场计算与可选 LLM 增强。

## ADDED Requirements

### Requirement: SPEC-DSUM-API-001 摘要响应兼容
`GET /desktop/summary` SHALL 继续返回 `current`、`timeline` 和 `behaviorAdvice`。响应 MAY 增加来源与新鲜度元数据；未识别这些字段的客户端 MUST 仍能渲染主内容。接口 MUST NOT 因为快照或 Wiki 组装失败而省略这三个顶层字段。

#### Scenario: 旧客户端忽略新元数据
- **WHEN** 客户端只读取 `current`、`timeline` 和 `behaviorAdvice`
- **THEN** 页面仍能展示当前状态、时间轴和建议卡片

### Requirement: SPEC-DSUM-SNAP-001 上次成功快照
系统 SHALL 持久化最近一次成功组装的摘要快照，并在后续 `GET /desktop/summary` 时作为可立即返回的权威副本。快照写入 SHALL 原子完成；损坏或无法读取时 SHALL 视为无快照，不得让摘要接口失败。

#### Scenario: 重启后仍有快照
- **WHEN** 上次摘要成功后应用重启，客户端再次请求 summary
- **THEN** 响应包含该快照中的已结束时段和建议，且不必等待全量历史重算

#### Scenario: 快照损坏
- **WHEN** 快照文件无法解析
- **THEN** 系统按无快照路径组装响应，接口仍返回 200 或等价成功摘要

### Requirement: SPEC-DSUM-UI-001 先出快照
打开或刷新 Agent 页时，若服务端或本次会话已有可用摘要快照，前端 SHALL 立即渲染该快照。有可用快照时 MUST NOT 先把建议区或时间轴清空为「分析行为数据中…」或「加载中…」。无快照时 MAY 显示加载占位，直到首个成功摘要返回。

#### Scenario: 再次打开已有快照
- **WHEN** 用户打开 Agent 页且存在上次成功快照
- **THEN** 建议区与时间轴立即显示快照内容，而不是加载占位

#### Scenario: 首次安装无快照
- **WHEN** 用户首次打开 Agent 页且没有可用快照
- **THEN** 界面可以显示加载占位，直到首个摘要响应到达

### Requirement: SPEC-DSUM-WIN-001 当前窗与已结束时段
未闭合「当前窗」SHALL 包括最近约 2 小时的 `current`，以及仍在进行的本地日 `today`。时段结束时刻已过去的条目 SHALL 视为已结束，包括昨天、前天，以及当天 12:00 之后的上午。跨度条目（本周、最近两周、本月）SHALL 由已结束子段与当前窗拼装，不得把整段未结束跨度当作单一现场 LLM 目标。

#### Scenario: 下午打开页面
- **WHEN** 本地时间不早于 12:00 且存在上午条目
- **THEN** 上午视为已结束时段，昨天与前天同样视为已结束

#### Scenario: 本周跨度
- **WHEN** 时间轴包含本周条目且本周尚未结束
- **THEN** 本周由已完成日子段与今天当前窗拼装，而不是对整周再次现场 LLM 增强

### Requirement: SPEC-DSUM-LIVE-001 只现算当前窗
现场查询窗口 / AFK 事实与可选 LLM 增强 SHALL 只作用于当前窗。已结束时段 MUST NOT 因为打开页面或定时刷新而再次调用 LLM。当前窗本地事实 SHALL 在刷新时更新；当前窗 LLM 增强仅在快照缺失、本地事实指纹变化或快照当前窗超过新鲜度阈值时触发。

#### Scenario: 重复打开只刷新当前窗
- **WHEN** 已存在快照且已结束时段 Wiki 摘要未变化
- **THEN** 系统只更新当前窗事实，不对昨天或前天再次调用 LLM

#### Scenario: 当前窗事实未变
- **WHEN** 当前窗本地事实指纹与快照一致且未超过新鲜度阈值
- **THEN** 系统复用快照中的当前窗文案，不发起新的 LLM 增强

### Requirement: SPEC-DSUM-WIKI-001 已结束时段复用 Wiki
已结束时段的 headline、insight 与摘要文案 SHALL 优先来自 `llm-wiki.db` 中对应层级的 `SUMMARIZED` 条目。Wiki 为桌面时间轴上这些时段的摘要权威；ActivityWatch summary bucket 投影若存在，MUST NOT 取代 Wiki 权威。

#### Scenario: 昨天已有 Wiki 日摘要
- **WHEN** 昨天存在 `SUMMARIZED` 的 DAY 条目
- **THEN** 时间轴昨天条目使用该 Wiki 摘要，而不是现场生成新文案

#### Scenario: 高层级已完成
- **WHEN** 本周存在 `SUMMARIZED` 的 WEEK 条目
- **THEN** 时间轴本周条目使用该 Wiki 摘要

### Requirement: SPEC-DSUM-FALL-001 Wiki 缺失时的降级
已结束时段在 Wiki 为缺失、PENDING、FAILED 或 SKIPPED 时，SHALL 使用本地事实填充该条目且不得为补齐该时段等待或调用 LLM。跨度条目在父级未完成时 SHALL 拼装可用子级 `SUMMARIZED` 条目与当前窗；不得把部分结果描述为完整 Wiki 历史。Wiki 整体不可用 MUST NOT 使当前窗或建议失败。

#### Scenario: 昨天 Wiki 仍为 PENDING
- **WHEN** 昨天 DAY 条目状态为 PENDING
- **THEN** 时间轴昨天条目显示本地事实，接口不等待该 Wiki 生成完成

#### Scenario: Wiki 已关闭
- **WHEN** wiki.enabled=false 或 Wiki store 不可用
- **THEN** 已结束时段使用本地事实，当前窗与建议仍可返回

### Requirement: SPEC-DSUM-ADV-001 建议随快照复用
行为建议 SHALL 写入摘要快照。后续请求在快照仍有效且当前窗事实未触发失效时 MUST 复用该建议，不得仅因页面重开或定时刷新重新生成。当前窗事实指纹变化或快照建议缺失时，SHALL 最多新生成一条建议。

#### Scenario: 定时刷新复用建议
- **WHEN** 自动刷新发生且当前窗事实指纹未变
- **THEN** 响应中的 behaviorAdvice 与快照一致，不新增建议 LLM 调用

### Requirement: SPEC-DSUM-POLL-001 定时刷新范围
Agent 页定时刷新 SHALL 只请求摘要增量语义：更新当前窗，并在 Wiki 新完成时替换对应已结束条目。刷新 MUST NOT 先清空已渲染内容。切到其他 tab 再回到 Agent tab MUST NOT 仅因切 tab 而重拉全量摘要。

#### Scenario: 停留在 Agent 页刷新
- **WHEN** 定时刷新完成且已有渲染内容
- **THEN** 已结束时段保持可见，只有当前窗或新完成的 Wiki 条目发生变化

#### Scenario: 往返其他 tab
- **WHEN** 用户从 Agent 切到会话或文件后再切回 Agent
- **THEN** 已渲染的建议和时间轴仍在，不回到加载占位

### Requirement: SPEC-DSUM-PRV-001 快照与时间轴隐私
快照、摘要响应和前端缓存 MUST NOT 包含完整 OCR/UIA 原文、API key、base URL、完整用户配置或完整 prompt。允许保存应用名、标题事实、聚合指标、Wiki 摘要、建议文案和新鲜度元数据。

#### Scenario: 快照不含密钥
- **WHEN** 系统写入或返回摘要快照
- **THEN** 内容不含 API key、完整配置或无障碍正文

### Requirement: SPEC-DSUM-ERR-001 错误隔离
当前窗计算、Wiki 查询、建议生成或 LLM 增强失败 MUST NOT 使整个摘要接口失败，也 MUST NOT 在已有快照时把 Agent 页打回空白加载占位。部分失败 SHALL 保留可展示的快照或本地事实，并在受影响条目上使用降级内容。

#### Scenario: 当前窗 LLM 超时
- **WHEN** 当前窗 LLM 增强失败但本地事实和快照可用
- **THEN** 响应使用本地当前窗事实与既有快照时段，接口仍然成功
