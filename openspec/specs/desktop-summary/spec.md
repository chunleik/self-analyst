# 桌面摘要与时间轴

## Purpose

定义看板单栏时间轴的展示与交互，以及摘要如何先展示上次成功快照、复用已结束时段的 Wiki 摘要，并只对未闭合当前窗做现场计算与可选 LLM 增强。

## Requirements

### Requirement: SPEC-DSUM-API-001 摘要响应兼容
`GET /desktop/summary` SHALL 继续返回 `current`、`timeline` 和 `behaviorAdvice`。响应 MAY 增加来源与新鲜度元数据；未识别这些字段的客户端 MUST 仍能渲染主内容。接口 MUST NOT 因为快照或 Wiki 组装失败而省略这三个顶层字段。

#### Scenario: 旧客户端忽略新元数据
- **WHEN** 客户端只读取 `current`、`timeline` 和 `behaviorAdvice`
- **THEN** 页面仍能展示当前状态、时间轴和建议卡片

### Requirement: SPEC-DSUM-SNAP-001 上次成功快照
系统 SHALL 持久化最近一次成功组装且统计口径、日历版本与时区兼容的摘要快照，并在后续 `GET /desktop/summary` 时作为可立即返回的权威副本。快照写入 SHALL 原子完成；损坏、无法读取或版本不兼容时 SHALL 视为无快照，不得让摘要接口失败。

#### Scenario: 重启后仍有快照
- **WHEN** 上次摘要成功且快照版本仍兼容时应用重启，客户端再次请求 summary
- **THEN** 响应包含该快照中的已结束时段和建议，且不必等待全量历史重算

#### Scenario: 快照损坏
- **WHEN** 快照文件无法解析
- **THEN** 系统按无快照路径组装响应，接口仍返回 200 或等价成功摘要

### Requirement: SPEC-DSUM-UI-001 先出快照
打开或刷新看板时，若服务端或本次会话已有可用摘要快照，前端 SHALL 立即渲染快照中的时间轴。已有快照时 MUST NOT 先清空时间轴为加载占位。无快照时 MAY 显示时间轴加载占位，直到首个成功摘要返回。

#### Scenario: 再次打开已有快照
- **WHEN** 用户打开看板且存在上次成功快照
- **THEN** 时间轴立即显示快照内容，而不是加载占位

#### Scenario: 首次安装无快照
- **WHEN** 用户首次打开看板且没有可用快照
- **THEN** 时间轴可以显示加载占位，直到首个摘要响应到达

### Requirement: SPEC-DSUM-WIN-001 当前窗与已结束时段
未闭合「当前窗」SHALL 包括最近约 2 小时的 `current`，以及仍在进行的统计日 `today`。today SHALL 从最近一次本地 04:00 开始；昨天、前天 SHALL 为此前完整统计日。上午 SHALL 为当前统计日的 04:00 至 12:00，于其 12:00 结束后展示，包括次日 04:00 前。时段结束时刻已过去的条目 SHALL 视为已结束。跨度条目（本周、最近两周、本月）SHALL 由已结束子段与当前窗拼装，不得把整段未结束跨度当作单一现场 LLM 目标。本周和本月 SHALL 按统计日日期从周一或月首 04:00 开始；最近两周 SHALL 保持截止当前时刻的滚动 14 天窗口，部分相交的子段只贡献交集事实，不得把整段子摘要作为完整覆盖。

#### Scenario: 下午打开页面
- **WHEN** 当前统计日的 12:00 已过去且存在上午条目
- **THEN** 上午视为已结束时段，昨天与前天同样视为已结束

#### Scenario: 本周跨度
- **WHEN** 时间轴包含本周条目且本周尚未结束
- **THEN** 本周由已完成统计日子段与今天当前窗拼装，而不是对整周再次现场 LLM 增强

#### Scenario: 凌晨打开
- **WHEN** 用户于本地 03:00 打开时间轴
- **THEN** 今天开始于前一日 04:00，昨天是其前一个完整统计日，当前仍为最近约两小时

### Requirement: SPEC-DSUM-LIVE-001 只现算当前窗
现场查询窗口 / AFK 事实与可选 LLM 增强 SHALL 只作用于当前窗。已结束时段 MUST NOT 因为打开页面或定时刷新而再次调用 LLM。当前窗本地事实 SHALL 在刷新时更新；当前窗 LLM 增强仅在快照缺失、本地事实指纹变化或快照当前窗超过新鲜度阈值时触发。

#### Scenario: 重复打开只刷新当前窗
- **WHEN** 已存在快照且已结束时段 Wiki 摘要未变化
- **THEN** 系统只更新当前窗事实，不对昨天或前天再次调用 LLM

#### Scenario: 当前窗事实未变
- **WHEN** 当前窗本地事实指纹与快照一致且未超过新鲜度阈值
- **THEN** 系统复用快照中的当前窗文案，不发起新的 LLM 增强

### Requirement: SPEC-DSUM-WIKI-001 已结束时段复用 Wiki
已结束时段的 headline、insight 与摘要文案 SHALL 优先来自 `llm-wiki.db` 中对应层级的 `SUMMARIZED` 条目。Wiki 为桌面时间轴上这些时段的摘要权威；事件 summary bucket 投影若存在，MUST NOT 取代 Wiki 权威。

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
看板定时刷新 SHALL 只请求摘要增量语义：更新当前窗，并在 Wiki 新完成时替换对应已结束条目。刷新 MUST NOT 先清空已渲染内容。切到其他 tab 再回看板 MUST NOT 仅因切 tab 而重拉全量摘要。

#### Scenario: 停留在 Agent 页刷新
- **WHEN** 定时刷新完成且已有渲染内容
- **THEN** 已结束时段保持可见，只有当前窗或新完成的 Wiki 条目发生变化

#### Scenario: 往返其他 tab
- **WHEN** 用户从看板切到会话或文件后再回看板
- **THEN** 已渲染的时间轴仍在，不回到加载占位

### Requirement: SPEC-DSUM-PRV-001 快照与时间轴隐私
快照、摘要响应和前端缓存 MUST NOT 包含完整 OCR/UIA 原文、API key、base URL、完整用户配置或完整 prompt。允许保存应用名、标题事实、聚合指标、Wiki 摘要、建议文案和新鲜度元数据。

#### Scenario: 快照不含密钥
- **WHEN** 系统写入或返回摘要快照
- **THEN** 内容不含 API key、完整配置或无障碍正文

### Requirement: SPEC-DSUM-ERR-001 错误隔离
当前窗计算、Wiki 查询、建议生成或 LLM 增强失败 MUST NOT 使整个摘要接口失败，也 MUST NOT 在已有快照时把看板打回空白加载占位。部分失败 SHALL 保留可展示的快照或本地事实，并在受影响条目上使用降级内容。

#### Scenario: 当前窗 LLM 超时
- **WHEN** 当前窗 LLM 增强失败但本地事实和快照可用
- **THEN** 响应使用本地当前窗事实与既有快照时段，接口仍然成功

### Requirement: SPEC-DSUM-UI-002 看板单栏时间轴
原 Agent 导航 SHALL 在中文下显示“看板”，英文下显示“Dashboard”。正文 SHALL 仅显示时间轴，以单栏占满可用宽度并保留正常页边距。系统 MUST NOT 显示上方行为建议、右侧未来任务及其加载、空状态或残留侧栏。全局导航、状态栏、设置入口 SHALL 保持可用。

#### Scenario: 打开看板
- **WHEN** 摘要包含建议、时间轴且已有任务，用户打开看板
- **THEN** 正文只显示时间轴，利用原两栏可用宽度

#### Scenario: 最小窗口与空数据
- **WHEN** 用户在 800×600 窗口打开无摘要数据的看板
- **THEN** 仅时间轴展示适当加载或空状态，无建议或任务占位，无布局导致的横向溢出

#### Scenario: 切换语言
- **WHEN** 用户切换中文或英文
- **THEN** 同一页面入口分别显示“看板”或“Dashboard”

### Requirement: SPEC-DSUM-UI-003 时间轴交互保持
看板 SHALL 保留时间轴时段顺序、内容、详情展开和继续追问，包括条目详情已有的建议内容。继续追问 SHALL 按既有会话契约携带上下文并聚焦输入框。页面简化 MUST NOT 删除任务数据或破坏会话任务上下文。

#### Scenario: 展开与追问
- **WHEN** 用户展开时间轴条目并选择继续追问
- **THEN** 原有详情可用，用户进入携带条目上下文的会话输入位置

#### Scenario: 会话任务能力
- **WHEN** 会话使用既有任务上下文
- **THEN** 该能力仍可用，不依赖看板任务区域存在

### Requirement: SPEC-DSUM-STAT-001 应用使用与未知活动展示
时间轴的应用排名、标题耗时及活跃时长 SHALL 遵循 activity-statistics 契约。未识别应用活动 SHALL 在详情单独显示且不参与主要应用结论；已知应用未知标题 SHALL 省略标题后缀。只有未知活动、全程非活跃、成功查询无事件与查询失败 SHALL 提供不同的准确文案。AFK 覆盖不足 SHALL 展示未扣除非活跃时间的估计说明，并随事实传入可选文案增强。

#### Scenario: 空标题
- **WHEN** Chrome 有有效使用时长但标题为 unknown
- **THEN** Chrome 可正常排名，headline 不包含 unknown 标题后缀

#### Scenario: 仅非活跃
- **WHEN** 区间内存在窗口但全部被 AFK 覆盖
- **THEN** 显示该时段无已记录活跃使用，不宣称主要使用 unknown

### Requirement: SPEC-DSUM-STAT-002 统计版本与快照
快照 SHALL 标记统计口径与时区，旧口径、旧时区或缺少版本的快照 MUST NOT 作为当前口径的权威内容复用。系统 SHALL 重新组装本地事实并原子保存兼容快照，旧文案及旧行为建议不能因事实指纹碰巧一致而保留；兼容快照继续遵循先出快照契约。

#### Scenario: 升级后旧快照
- **WHEN** 启动时存在按午夜及未扣 AFK 口径生成的快照
- **THEN** 服务端及前端缓存均不将其作为已更新结果展示，重新组装成功后展示新口径摘要

### Requirement: SPEC-DSUM-LIVE-002 当前窗共用标题事实
看板 current/today 的模型增强 SHALL 消费与 Wiki 相同语义的有界结构化标题事实，并执行任务证据及任务导向文案策略。当前窗 SHALL 使用独立的小输入预算和原有超时/缓存/调用限额；失败时 SHALL 返回本地事实与既有快照，不阻塞已结束摘要。事实指纹 SHALL 覆盖实际标题证据变化。

文案缓存 SHALL 按有效语言隔离，新鲜度 SHALL 依据文案生成时间而非最近响应组装时间；命中缓存 MUST NOT 无限延长文案寿命。微小活动时长增长 SHALL 使用既有时间分档避免每次刷新都调用模型。

#### Scenario: 相同应用内主题改变
- **WHEN** 应用排名不变而标题任务发生变化
- **THEN** 当前窗事实和指纹改变，后续增强能够表达新主题

#### Scenario: 超时与 Wiki 关闭
- **WHEN** 当前窗模型超时或 Wiki 后台未启用
- **THEN** 看板仍能查询本地标题事实并正常降级，不要求等待后台 Wiki 或启动多轮长摘要

#### Scenario: 历史时段
- **WHEN** 打开看板且昨天已有日摘要
- **THEN** 仍复用历史日摘要，不因共用事实构建而额外生成历史文案

#### Scenario: 持续刷新与语言变化
- **WHEN** 客户端持续刷新同一事实超过文案有效期，或切换有效语言
- **THEN** 文案按实际生成时间到期并允许重新生成，语言变化不复用旧语言文案

### Requirement: SPEC-DSUM-LIVE-003 引用派生与生成状态
当前窗 SHALL 与Wiki共用引用派生的应用及证据，模型不必重复生成确定字段。历史摘要因周期预算暂停或生成失败时，查询和时间轴详情 SHALL 展示简明原因与已消耗额度，同时继续展示本地事实。明确区分输入事实覆盖、合并后卡片覆盖和展示引用，不以引用数宣称完整语义覆盖。

#### Scenario: 暂停的历史日摘要
- **WHEN** 昨天的日摘要用尽跨重试额度
- **THEN** 界面或查询返回预算暂停及消耗信息，不无提示地显示为普通加载，也不因此阻止当前窗

#### Scenario: 多应用证据
- **WHEN** 当前窗任务引用多个实际应用的事实
- **THEN** 输出应用集合由这些引用派生，旧快照仍可读取
