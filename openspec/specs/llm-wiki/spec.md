# LLM Wiki 规格

## Purpose

定义本地多层时间摘要、历史发现与重试、Agent 时间查询、标题事实隐私边界，以及可选语义索引和 embedding 检索行为，使长期复盘不依赖原始屏幕正文。

## Requirements

### Requirement: SPEC-WIKI-GOAL-001..008 多级摘要与检索目标
系统 SHALL 支持 HOUR、HALF_DAY、DAY、WEEK、BIWEEK 和 MONTH 层级，并默认永久保存已生成摘要。
HOUR SHALL 从原始窗口、AFK 和内容标题事实生成；HALF_DAY 及以上 SHALL 从已完成的下级摘要生成。
Agent SHALL 能按时间范围查询摘要；可选语义检索 SHALL 能按模糊主题检索摘要与
任务片段。后台发现和补算 MUST NOT 阻塞应用启动。LLM 不可用时 MUST 保留可重试状态，不写规则兜底
伪摘要。Wiki 数据 MUST NOT 长期保存完整 OCR/UIA 正文。

#### Scenario: 低层级生成
- **WHEN** 已结束小时具有可用标题事实
- **THEN** 系统从该范围原始事实生成小时摘要

#### Scenario: 高层级生成
- **WHEN** 周、双周或统计月的预期下级摘要全部完成
- **THEN** 系统只使用这些下级摘要生成趋势摘要，不重新读取屏幕正文

#### Scenario: LLM 不可用
- **WHEN** due entry 需要摘要但模型未配置或调用失败
- **THEN** entry 保持 FAILED/PENDING 可重试，不写低质量占位摘要

### Requirement: SPEC-WIKI-ARCH-001..007、SPEC-WIKI-SEM-ARCH-001..006 服务边界与失败隔离
`llm-wiki.db` SHALL 是摘要、生成状态和语义文档元数据的权威；可重建向量索引 SHALL 独立存储。
后台摘要只能通过受控 store 读写 Wiki 状态，并通过 AW 事件存储读取原始事实。Agent SHALL 通过
WikiTools 查询，不直接访问数据库或向量索引。摘要生成失败 MUST NOT 阻塞其它时间块；embedding 或
向量索引失败 MUST NOT 回滚已完成摘要或普通 Wiki 查询。

#### Scenario: 摘要成功但 embedding 失败
- **WHEN** Wiki entry 已成功 SUMMARIZED，随后 embedding 请求或索引写入失败
- **THEN** 摘要继续可按时间查询，语义文档进入 FAILED 并等待重试

#### Scenario: Wiki 数据库初始化失败
- **WHEN** llm-wiki.db 无法初始化
- **THEN** 主服务继续启动，Wiki worker 和工具不可用并记录受限错误

### Requirement: SPEC-WIKI-FLOW-001..004、SPEC-WIKI-GEN-001..009 层级输入依赖
HOUR SHALL 直接聚合其时间范围内的原始事实。HALF_DAY SHALL 只聚合该半天内已完成的 HOUR。DAY SHALL 只聚合该日已完成的 HALF_DAY。WEEK SHALL 只聚合该周 SUMMARIZED DAY；BIWEEK SHALL 聚合两个连续 SUMMARIZED WEEK；MONTH SHALL 聚合同一统计月 SUMMARIZED DAY。预期子时间块未全部达到 SUMMARIZED 或 SKIPPED 时，父级 SHALL 保持 PENDING。

#### Scenario: 半天等待小时
- **WHEN** 一个已结束半天内仍有小时不存在、PENDING 或 FAILED
- **THEN** 该半天保持 PENDING，不读取原始标题生成摘要

#### Scenario: 日等待半天
- **WHEN** 同一天的两个半天尚未全部 SUMMARIZED 或 SKIPPED
- **THEN** DAY 保持 PENDING，不从原始事实拼接

#### Scenario: 父级等待依赖
- **WHEN** 周期内任一预期子时间块不存在、PENDING 或 FAILED
- **THEN** 父级保持 PENDING，不生成不完整趋势摘要

### Requirement: SPEC-WIKI-TIME-001..012 时间边界
所有周期 SHALL 使用生成时系统默认时区，并保存 timezone、ISO-8601 instant period_start 和 period_end；start 为闭区间、end 为开区间。HOUR SHALL 对齐整点；HALF_DAY SHALL 为统计日的 04:00 至 12:00 和 12:00 至次日 04:00；DAY SHALL 为本地 04:00 到次日 04:00。WEEK SHALL 使用周一 04:00 开始的 ISO 周；BIWEEK SHALL 为两个连续、以周一 04:00 分界的 ISO 周；MONTH SHALL 使用月首 04:00 至下月首日 04:00 的日历月。日、周、双周、月的归属 SHALL 依据统计日开始日期确定，并按本地日历构造边界。当前未结束的周期 MUST NOT 生成。没有任何可用数据的已结束周期 MAY 标记 SKIPPED 并记录原因。

#### Scenario: 当前开放周期
- **WHEN** 时间块的 period_end 晚于当前时刻或请求范围结束
- **THEN** worker 不处理或提前入队该周期

#### Scenario: 自然月
- **WHEN** 构造 MONTH 周期
- **THEN** 起止边界为同一时区月首 04:00 到下月首日 04:00，不使用 30 天滚动窗口

#### Scenario: 后半天跨午夜
- **WHEN** 本地时间进入次日 00:00 但尚未到 04:00
- **THEN** 前一统计日的后半天及 DAY 仍未结束，不能提前生成

### Requirement: SPEC-WIKI-DB-001..009、SPEC-WIKI-STAT-001..004 Wiki 存储与状态
数据库 SHALL 位于 `{memory.dir}/llm-wiki.db` 并创建父目录，schema 版本 SHALL 覆盖摘要表和语义文档
元数据。`(level, period_start, period_end, timezone, statistics_version, calendar_version)` MUST 唯一。entry status SHALL 为
PENDING/SUMMARIZED/FAILED/SKIPPED。摘要提交 SHALL 在单事务内更新状态、summary、metrics、任务片段、
source IDs 与时间；JSON 字段 MUST 为合法 JSON 或 null。

#### Scenario: 唯一周期 upsert
- **WHEN** 相同层级、边界、时区及统计版本被重复发现
- **THEN** store 返回或更新同一 entry，不创建重复周期

#### Scenario: 摘要事务
- **WHEN** 结构化摘要成功生成
- **THEN** summary、任务片段、metrics、状态与 summarizedAt 在同一事务中提交

### Requirement: SPEC-WIKI-MDL-001..008 摘要数据模型
任务片段 SHALL 包含最多 80 字符标题、最多 500 字符摘要、脱敏 evidence、应用列表和
high/medium/low confidence。metrics 的 activeSeconds、afkSeconds MUST 非负，topApps SHALL 按耗时
降序且默认最多 10 项；extra MAY 保存层级特有的有界指标。任务片段和 metrics MUST NOT 保存完整
OCR/UIA 原文。

#### Scenario: 任务片段规范化
- **WHEN** LLM 返回超长任务标题、摘要或非法 confidence
- **THEN** 结果被拒绝或规范化，不以无界原文写入数据库

### Requirement: SPEC-WIKI-SRC-001..006、010、011、013 标题事实来源与预算
事实 SHALL 来自当前主机的合并 window、AFK 和 content bucket；Wiki MUST NOT 直接查询逐 heartbeat 原始事件层。窗口事件 SHALL 用于应用耗时、切换次数和窗口标题样本；AFK 用于非活跃时间及逐应用有效时长扣除；content 只提供 app、系统标题、可选上下文标题和种类。任一投影 bucket 缺失或查询失败时，其他可用事实 MAY 继续生成。单次标题事实输入 SHALL 受 `wiki.prompt.maxContentChars` 限制，完整请求 SHALL 另受 `wiki.summary.maxRequestChars` 限制，单个窗口标题样本默认最多 160 字符；上下文标题 SHALL 按 app、effectiveTitle、contextKind 去重。Wiki MUST NOT 读取 `text_content`。

#### Scenario: content bucket 含旧正文
- **WHEN** 内容事件投影同时具有标题字段和旧 `text_content`
- **THEN** Wiki facts 只使用标题投影，prompt 和数据库不包含旧正文

#### Scenario: 部分 bucket 缺失
- **WHEN** AFK 或 content 投影 bucket 不存在，但 window 投影 bucket 有可用事件
- **THEN** 系统使用可用指标继续构造 facts，缺失指标置零或为空并明确覆盖不足；AFK 缺失时窗口耗时只能作为未扣除非活跃时间的估计

#### Scenario: Wiki 不读取永久原始层
- **WHEN** Wiki 生成小时、半天或天级事实
- **THEN** 系统读取事件投影且不调用桌面原始事件查询 API

### Requirement: SPEC-WIKI-SRC-020 默认事实来源桶标识
HOUR、HALF_DAY 和 DAY 的事实构建 SHALL 默认查询本机投影桶
`watcher-window_{hostname}`、`watcher-afk_{hostname}` 和 `watcher-content_{hostname}`。
当对应导入或外部兼容桶使用 `aw-watcher-window_*`、`aw-watcher-afk_*` 或 `aw-watcher-content_*` 时，
系统 SHALL 仍能把它们识别为同一来源种类。Wiki MUST NOT 把 `wiki-*` 时间线投影桶当作事实来源。

#### Scenario: 本产品默认窗口桶
- **WHEN** Wiki 为已结束小时构造事实且本机存在 `watcher-window_{hostname}` 投影
- **THEN** 窗口应用耗时、切换次数和标题样本来自该桶

#### Scenario: 导入窗口桶仍可识别
- **WHEN** 仅存在 `aw-watcher-window_{hostname}` 投影而无 `watcher-window_*` 桶
- **THEN** 系统仍从该导入桶读取窗口事实，不因前缀不是 `watcher-` 而跳过

### Requirement: SPEC-WIKI-RAW-001 Wiki 派生版本与覆盖信息
每个新生成的 Wiki 条目 SHALL 记录事实构建版本、所消费事件存储版本以及各来源 bucket 的时间覆盖或缺失状态。Wiki 重建 MUST 只修改 Wiki 和语义派生数据，不得修改权威合并事件；覆盖不完整时查询结果 SHALL 能向 Agent 和桌面说明不完整性。

#### Scenario: 投影版本升级后重建 Wiki
- **WHEN** 事件投影算法或 Wiki 事实构建版本发生变化并触发重建
- **THEN** 新条目记录新版本和覆盖信息，旧 Wiki 派生数据可被替换，但权威合并事件保持不变

#### Scenario: 原始完整但投影尚有延迟
- **WHEN** 嵌入式合并存储已成功提交时间段内事件
- **THEN** Wiki 不虚构 raw 投影延迟，以实际 bucket 查询和时间覆盖决定完整性

### Requirement: SPEC-WIKI-GEN-010..012 结构化摘要校验
LLM 摘要响应 SHALL 解析为包含非空 summary 和 primaryTask 的结构化结果；JSON 解析失败或必需字段
缺失时 entry SHALL FAILED。应用耗时、activeSeconds、afkSeconds 和切换次数等本地指标 MUST 以本地
聚合值为准，LLM MUST NOT 覆盖。

#### Scenario: 非法摘要 JSON
- **WHEN** LLM 返回无法解析的 JSON 或缺少 summary/primaryTask
- **THEN** entry 标记 FAILED，保存受限错误并设置重试时间

#### Scenario: LLM 修改本地指标
- **WHEN** 模型响应中的 metrics 与本地聚合不同
- **THEN** 持久化使用本地指标，只接受模型生成的摘要类字段

### Requirement: SPEC-WIKI-WKR-001..023 Wiki worker 发现、处理与重试
wiki.enabled 且 store、Agent、AW 和内容持久化可用时，系统 SHALL 使用单线程后台 worker。启用 backfill
时启动阶段 SHALL 发现最近 7 天缺失周期；关闭 backfill 时首次轮次仍 SHALL 对账最近已结束小时，之后
持续发现新结束周期。发现 SHALL 幂等，不覆盖 SUMMARIZED。每轮默认最多处理一个 due entry，优先
低层级 PENDING，再处理到期 FAILED；父级只有依赖齐备时处理。失败 SHALL 增加 retry_count、保存受限
last_error，并按递增退避设置 next_retry_at。对账失败 MUST NOT 推进游标。关闭时 SHALL 等待正在提交
的事务完成后停止。

#### Scenario: 关闭历史 backfill
- **WHEN** wiki.backfill.enabled=false 且 worker 首次运行
- **THEN** 系统仍发现最近一个已结束小时，之后持续发现新结束周期，不补齐 7 天历史

#### Scenario: 单条失败不阻塞队列
- **WHEN** 一个 entry 生成失败或父级依赖不完整
- **THEN** worker 记录失败/等待状态，并继续扫描其它可处理低层级或到期条目

#### Scenario: 对账失败
- **WHEN** 时间块发现过程中读取或写入失败
- **THEN** 发现游标不推进，下一轮重试同一窗口

### Requirement: SPEC-WIKI-TOL-001..012 时间范围查询工具
WikiTools SHALL 按 ISO start/end 和可选 level 查询摘要；level 为空时 SHALL 按跨度选择粒度：不超过
6 小时优先 HOUR，6 小时到 2 天优先 HALF_DAY/DAY，2 到 14 天优先 DAY，14 到 60 天优先 WEEK，
超过 60 天优先 MONTH。首选粒度缺失时 SHALL 报告缺失并 MAY 返回可用降级粒度。结果 SHALL 包含
pending、failed、skipped 或缺失区间信息，MUST NOT 返回完整 OCR/UIA 原文。

#### Scenario: 自动粒度选择
- **WHEN** Agent 查询 30 天时间范围且未指定 level
- **THEN** 工具优先返回 WEEK 摘要，并说明任何未完成区间

#### Scenario: 首选粒度缺失
- **WHEN** 首选层级没有摘要但其它层级有数据
- **THEN** 工具返回缺失提示和可用降级结果，不声称完整覆盖

### Requirement: SPEC-WIKI-SEM-DB-001..007 语义文档元数据
每个语义文档 SHALL 具有稳定 docId、entryId、ENTRY_SUMMARY/TASK_SEGMENT 类型、level、period、
规范化 text_hash、embedding model、dimensions 和 PENDING/INDEXED/FAILED/STALE 状态。文本、模型或维度
变化 SHALL 产生新 hash 或把旧文档标记 STALE。SQLite metadata MUST NOT 保存 embedding 向量；entry
删除或重建时相关文档 SHALL STALE 或从向量索引删除。

#### Scenario: embedding 配置变化
- **WHEN** model 或 dimensions 与已索引文档不同
- **THEN** 旧语义文档标记 STALE，并等待使用新配置重建

### Requirement: SPEC-WIKI-SEM-IDX-001..009 可重建向量索引
每个 SUMMARIZED entry SHALL 产生一个 ENTRY_SUMMARY 文档，每个 task segment SHALL 产生一个
TASK_SEGMENT 文档。索引文本 SHALL 只由摘要、primaryTask、层级、周期、任务标题，或任务片段的标题、
摘要、apps、confidence 组成，MUST NOT 包含 evidence 原文或 OCR/UIA text_content。向量索引 SHALL
保存 doc/entry/type/level/period、规范化文本、安全 matchedText 和 cosine float vector，并能从 Wiki
数据库和语义元数据全量重建。

#### Scenario: task evidence 含原始文本
- **WHEN** 任务片段 evidence 包含原始 OCR/UIA 文本
- **THEN** 语义索引文本和 matchedText 不包含该 evidence

#### Scenario: 删除 entry
- **WHEN** Wiki entry 被删除或重建
- **THEN** 关联索引文档被删除或标记待重建，不继续出现在查询结果

### Requirement: SPEC-WIKI-EMB-001..007 embedding 客户端边界
embedding SHALL 通过 OpenAI-compatible `/embeddings` 接口生成，发送 model、input，并 MAY 按配置发送
float encoding_format 和 dimensions。返回向量长度 MUST 等于配置 dimensions，否则文档 FAILED。
请求失败 MUST NOT 改变 Wiki 摘要状态。API key、请求体、完整索引文本和向量 MUST NOT 写入日志或
last_error；客户端对调用方 SHALL 只暴露生成向量所需的最小接口。

#### Scenario: 向量维度不匹配
- **WHEN** embedding 服务返回长度与配置不同的向量
- **THEN** 当前语义文档标记 FAILED，普通摘要保持 SUMMARIZED

#### Scenario: embedding 服务错误
- **WHEN** HTTP 请求失败或响应无效
- **THEN** 保存受限错误和重试时间，不记录 key、完整输入或响应体

### Requirement: SPEC-WIKI-SEM-WKR-001..008 embedding worker
wiki semantic、embedding 和 API key 均可用时，系统 SHALL 使用独立单线程 worker，不阻塞主服务或摘要
worker。每轮默认最多处理一个 PENDING，再处理一个到期 FAILED。成功顺序 SHALL 先 commit 向量索引，
再把 metadata 标记 INDEXED；索引写入失败 MUST NOT 标记成功。关闭时 SHALL 等待当前 SQLite/Lucene
提交完成或安全终止。

#### Scenario: 新摘要入队
- **WHEN** 新 entry 变为 SUMMARIZED
- **THEN** worker 创建一个 entry 文档和每个任务片段文档的 PENDING metadata

#### Scenario: Lucene 写入失败
- **WHEN** embedding 已生成但向量索引 commit 失败
- **THEN** 文档保持可重试失败状态，不标记 INDEXED

### Requirement: SPEC-WIKI-SEM-TOL-001..007 语义搜索工具
semanticSearchWiki SHALL 要求非空 query，并使用 embedding model 生成查询向量；start、end、level MAY
作为过滤条件，topK 默认来自配置且 MUST 限制为 1..50。结果 SHALL 包含 score、docType、entryId、
level、period、summary、primaryTask 和安全 matchedText，MUST NOT 返回原始 OCR/UIA 文本、向量或 API key。
语义能力不可用或查询失败时 SHALL 返回结构化错误和 queryWiki fallbackSuggestion，不得回退为无过滤
全量输出。

#### Scenario: 模糊主题搜索
- **WHEN** 用户提供主题描述但没有明确时间范围
- **THEN** Agent MAY 优先调用语义搜索并按相似度返回有界结果

#### Scenario: 语义索引不可用
- **WHEN** embedding 或向量索引未配置或查询失败
- **THEN** 工具返回结构化不可用错误，并建议使用时间范围 queryWiki

### Requirement: SPEC-WIKI-AGT-001..005 Agent 使用 Wiki
Agent 指令 SHALL 说明 Wiki 用于历史复盘。用户询问明确时间段的任务、趋势或对比时，Agent SHOULD
优先使用时间查询；只有主题而无时间范围时 SHOULD 尝试语义搜索。工具返回 pending/failed 时 MUST
说明结果不完整。Agent MUST NOT 在 Wiki 无结果时直接声称没有数据，除非必要的事件原始查询
也无可用事实。

#### Scenario: Wiki 区间未完成
- **WHEN** 查询结果包含 pending 或 failed 周期
- **THEN** Agent 明确说明仍在生成或生成失败，不把部分结果描述为完整历史

### Requirement: SPEC-WIKI-CFG-001..009 配置与独立降级
wiki.enabled=false 时 SHALL 不创建 Wiki store、worker 或工具。backfill=false 时 SHALL 不补齐 7 天历史，
但 worker MAY 持续发现新周期。prompt max chars 小于有效下限时 SHALL 使用默认值。semantic=false、
embedding=false、key 缺失、dimensions 非法或向量索引初始化失败时 SHALL 禁用语义 worker和语义查询，
但普通 Wiki 摘要和时间查询在其它依赖可用时继续工作。topK 超出 1..50 SHALL 回退默认 8，dimensions
非法 SHALL 回退默认 1024。

#### Scenario: 关闭语义能力
- **WHEN** wiki.semantic.enabled=false 或 embedding.enabled=false
- **THEN** 不发起 embedding 请求，普通摘要继续可用，语义工具返回结构化不可用错误

### Requirement: SPEC-WIKI-PRV-001..011 Wiki 隐私边界
Wiki 数据库、prompt、日志和向量索引 MUST NOT 保存或输出完整 text_content、OCR/UIA 原文、API key、
完整用户配置、完整 prompt、embedding 请求体或向量。允许保存应用名、标题事实、聚合指标、摘要、任务
片段、脱敏 evidence 和规范化索引文本。prompt SHALL 有硬字符上限；last_error SHALL 截断并排除完整
输入。matchedText SHALL 只来自安全规范化摘要文本。

#### Scenario: 摘要或 embedding 失败
- **WHEN** 上游输入含正文或密钥且生成调用失败
- **THEN** last_error 和日志只保存受限错误，不回显完整 prompt、正文或 key

### Requirement: SPEC-WIKI-ERR-001..009 错误隔离
Wiki 数据库、单时间块、原始 bucket、LLM、embedding 或向量索引失败 MUST NOT 使主服务崩溃。
单 entry 失败 SHALL FAILED 并重试；全部原始 bucket 失败 SHALL FAILED，完全无事件 SHALL SKIPPED。
向量索引或 embedding 失败 SHALL 只禁用/重试语义能力，普通摘要状态不变。语义查询失败 MUST NOT
回退到无过滤全量摘要。

#### Scenario: 全部原始 bucket 查询失败
- **WHEN** window、AFK 和 content 查询均失败
- **THEN** 当前 entry 标记 FAILED 并设置重试，而不是写空摘要或 SKIPPED

#### Scenario: 时间块没有事件
- **WHEN** 所有查询成功但范围内没有可用事实
- **THEN** entry 标记 SKIPPED 并记录无数据原因

### Requirement: SPEC-WIKI-NON-001、004..006 功能边界
当前能力 MUST NOT 依赖独立 Wiki 浏览页、手动编辑摘要、跨设备同步、账号或云端存储才能工作。
历史摘要 SHALL 默认永久保留，除非未来显式引入保留策略。当前系统 MAY 把完成摘要投影到本地
事件 summary bucket 供时间线消费；该行为不改变 llm-wiki.db 的摘要权威。

#### Scenario: 用户需要历史复盘
- **WHEN** 没有独立 Wiki 页面
- **THEN** 用户仍可通过 Agent、时间线投影和 WikiTools 查询摘要

### Requirement: SPEC-WIKI-PROJ-010 Wiki 时间线投影桶
系统 MAY 把已完成摘要投影到本机时间线桶，权威仍是 `llm-wiki.db`。投影桶 ID SHALL 为
`wiki-hourly_{hostname}`、`wiki-halfday_{hostname}` 和 `wiki-daily_{hostname}`，client SHALL 为 `wiki`。
这些桶 MUST NOT 使用 `watcher-` 或 `aw-watcher-` 前缀。

#### Scenario: 小时摘要进入时间线桶
- **WHEN** 近七日存在 SUMMARIZED 的 HOUR 条目且时间线投影启用
- **THEN** 系统写入 `wiki-hourly_{hostname}`，事件含标题与摘要字段

#### Scenario: Wiki 桶不是采集器
- **WHEN** 创建或更新 Wiki 时间线投影桶
- **THEN** bucket client 为 `wiki`，ID 不以 `watcher-` 开头

### Requirement: SPEC-WIKI-DSK-001 桌面时间轴消费已结束 Wiki
桌面时间轴对已结束时段 SHALL 以 `llm-wiki.db` 中对应层级的 `SUMMARIZED` 条目为摘要权威，MUST NOT 为这些时段再次现场生成 LLM 文案。父级跨度未完成时 SHALL 拼装可用子级 `SUMMARIZED` 条目；PENDING、FAILED、SKIPPED 或缺失 MUST NOT 阻塞当前窗。该消费路径 MUST NOT 改变 Wiki worker 的发现、生成或补算职责，也不得把事件 summary bucket 投影当作比 Wiki 更高的权威。

#### Scenario: 已结束日复用 Wiki
- **WHEN** 昨天存在 SUMMARIZED DAY 条目且客户端请求桌面 summary
- **THEN** 时间轴昨天条目使用该 Wiki 摘要，不对该日发起新的 summary LLM 调用

#### Scenario: 未完成跨度不阻塞页面
- **WHEN** 本周 WEEK 条目仍为 PENDING 但若干已结束 DAY 已 SUMMARIZED
- **THEN** 时间轴使用这些 DAY 摘要与当前窗拼装本周，并继续返回当前窗

### Requirement: SPEC-WIKI-STAT-020 共享有效活动事实
Wiki 应用耗时、活跃时长、非活跃时长及未知活动 SHALL 遵循 activity-statistics 契约。标题事实仍 SHALL 仅来自允许的事件投影。AFK 覆盖缺失 SHALL 在结构化事实、指标及来源覆盖中反映，不得以零值暗示已确认无非活跃时间；这些统计只用于任务优先级与置信度判断，MUST NOT 要求在 Wiki 自然语言摘要中复述。高层级 SHALL 仅聚合当前口径、边界一致的下级指标，并保留未知活动和覆盖信息。

#### Scenario: Wiki 与桌面一致
- **WHEN** Wiki 与桌面查询同一主机、时区及时间区间的同一事件快照
- **THEN** 二者在展示格式化前具有一致的应用有效时长、未知活动与 AFK 指标

#### Scenario: 覆盖不足仍保留统计
- **WHEN** AFK 来源缺失、不完整或存在未覆盖活动
- **THEN** 结构化覆盖与精确指标保持可用，任务文案采用谨慎措辞，任务片段置信度最高为 medium，不解释 AFK 统计原因

### Requirement: SPEC-WIKI-STAT-021 历史统计版本隔离与重算
Wiki SHALL 区分事实统计和周期边界版本。旧版或缺少版本的条目 MUST NOT 作为新口径桌面摘要、父级依赖或默认 Wiki 检索的权威结果；旧条目 SHALL 保留用于追溯。后台 SHALL 按新边界发现历史事实并可恢复、幂等地重算受影响周期，父级等待当前版本依赖，受影响语义索引同步隔离或更新。此过程 MUST NOT 阻塞启动或看板，MUST NOT 改写永久原始事件，且 SHALL 遵守既有 LLM 预算及重试策略。

#### Scenario: 已有午夜日摘要
- **WHEN** 系统发现旧午夜边界的 SUMMARIZED DAY 及依赖它的周摘要
- **THEN** 旧结果不进入新口径结果，后台按 04:00 周期构建新结果，等待期间看板使用明确来源的本地事实

#### Scenario: 重算中断
- **WHEN** 历史重算期间程序重启或 LLM 不可用
- **THEN** 已完成的新版本结果保留，未完成工作可恢复且不重复生成同一当前版本周期

### Requirement: SPEC-WIKI-SRC-030 结构化活动标题事实
小时、半天和日摘要 SHALL 使用包含应用、标题、事实类型、来源事件引用及时间区间的结构化标题样本。窗口样本时长 SHALL 来自共享统计的有效区间；上下文样本 SHALL 与同应用、同系统窗口标题的有效窗口区间关联，无法关联时仅报告观察区间且 MUST NOT 将其时长计入活动总量。相同应用、标题及类型 SHALL 去重展示，同时保留总出现次数及有界的分离区间代表，不得把中间空隙描述为连续活动。未知标题 SHALL 不作为任务证据。

#### Scenario: 相同标题往返
- **WHEN** 活动顺序为 A、B、A
- **THEN** A 的标题只出现一次，出现次数和分离区间被保留，B 不被合并进 A 的时长

#### Scenario: AFK 与上下文重叠
- **WHEN** 上下文观察区间部分被 AFK 覆盖
- **THEN** 可关联上下文的活动区间只保留扣除 AFK 后的交集，且不与窗口指标重复累加

### Requirement: SPEC-WIKI-SRC-031 时间覆盖采样与独立预算
系统 SHALL 在同一标题事实总字符预算内为窗口与额外上下文各预留独立额度，并可在一类未用完额度时分配余量。预算 SHALL 计入发送给模型的紧凑样本序列化开销。系统 SHALL 确定性地按时间分层选取代表事实，在可容纳时覆盖不同时间段，并兼顾有效活动时长、不同应用及同一应用内不同标题主题；未见应用 MUST NOT 无条件压过主要应用的其他主题，观察区间 MUST NOT 按已确认有效时长排序。超长样本不得阻止后续可容纳样本。输出 SHALL 说明候选数、选中数和被省略的区间数量，不得把采样结果宣称为完整事件列表。

#### Scenario: 重复窗口不能挤占上下文
- **WHEN** 窗口标题大量重复且同时存在额外上下文标题
- **THEN** 重复标题被归组，额外上下文仍能使用其独立预算

#### Scenario: 多时段与输入顺序
- **WHEN** 不同时间段存在多个活动且输入顺序变化
- **THEN** 相同预算产生相同样本，预算足够时不同时间段均有代表

#### Scenario: 主要应用的不同主题
- **WHEN** 同一主要应用具有多个不同标题主题，同时出现多个很短的辅助应用活动
- **THEN** 采样保留主要应用的多个重要主题，不因辅助应用未见过而优先排除所有后续主题

#### Scenario: 极小预算
- **WHEN** 预算不足以容纳结构化样本
- **THEN** 系统省略该样本并报告未选中数量，不超预算、不截断 JSON 或 Unicode 字符

### Requirement: SPEC-WIKI-GEN-020 第一阶段摘要兼容性
事实处理 SHALL 不改变活跃、AFK、应用耗时及切换次数等完整本地指标。可容纳的摘要 SHALL 只调用一次模型；超长输入 SHALL 遵守有界分层生成契约，不以无上限循环修复响应。结构化事实 SHALL 仅包含既有标题事实白名单，不读取正文或文件内容。新事实和提示词 SHALL 记录更新版本，既有已完成摘要 SHALL 保留且不因本次调整自动触发付费重算。

#### Scenario: 采样预算不同
- **WHEN** 同一活动区间使用不同标题预算生成摘要
- **THEN** 选中事实可以不同，但完整本地指标完全一致，短输入保持单次调用，长输入报告生成预算与省略情况

#### Scenario: 历史正文
- **WHEN** 历史内容事件仍携带正文属性
- **THEN** 结构化事实和模型输入不包含该属性或正文

### Requirement: SPEC-WIKI-SRC-032 跨来源标题去重
内容源回退到系统窗口标题时，系统 SHALL 去除已由同应用、同完整标题有效窗口覆盖的重复观察区间，保留窗口事实及其来源引用。未覆盖部分 SHALL 保留为观察，具有额外聊天或文章标题语义的内容事实 MUST NOT 被系统标题去重规则删除。去重 MUST NOT 改变全量统计指标，也不得把不相交的同名观察误当作重复。

#### Scenario: 完全重复的系统标题
- **WHEN** 内容源仅重复某窗口的系统标题且观察区间被有效窗口完全覆盖
- **THEN** 模型只接收一个窗口标题事实，不另为重复内容视角消耗额度

#### Scenario: 部分覆盖与独立上下文
- **WHEN** 内容观察仅部分落在有效窗口内，或提供不同于系统标题的聊天/文章标题
- **THEN** 前者保留未覆盖观察且不增加活跃时长，后者保留相应上下文事实

### Requirement: SPEC-WIKI-SRC-033 紧凑模型投影与默认预算
本地结构化事实 SHALL 保留事实 ID、来源、应用、完整标题、类型、有效秒数、出现次数、有界来源事件引用及原始精度代表区间。模型输入 SHALL 使用有界紧凑投影并说明时间、观察标记和省略语义；分层主题生成可使用标题、字典索引、时间层和观察标记的瘦投影，未展开的统计及来源仍通过事实 ID 在本地追溯。`wiki.prompt.maxContentChars` 默认 SHALL 为24000字符，显式合法配置继续生效；配置、模板及实际运行默认值 SHALL 一致。单次模型请求 SHALL 同时满足完整请求大小限制。扩大预算 MUST NOT 自动重算已有摘要，分层调用 SHALL 受独立总调用数限制。

#### Scenario: 同一预算的紧凑表示
- **WHEN** 多个短标题有若干时间区间及较长来源标识
- **THEN** 模型输入省去重复展开的区间及来源标识，仍可通过事实 ID 关联本地完整事实，序列化字符数不超过配置预算

#### Scenario: 默认与显式配置
- **WHEN** 未配置标题事实预算或使用无效的过小值
- **THEN** 使用24000字符默认值，配置模板一致；用户显式合法设置12000时仍使用12000

### Requirement: SPEC-WIKI-GEN-021 任务导向文案与统计隔离
新生成 Wiki 的 summary、primaryTask、任务片段 title/summary/evidence SHALL 聚焦活动、项目与技术主题，MUST NOT 复述 AFK 覆盖、活跃/离开或应用使用时长、覆盖率及内部采样统计。指标 MAY 作为内部排序和置信度依据，持久化 metrics 和 sourceCoverage SHALL 保持原有语义。证据不足时 SHALL 采用有限的活动描述，不把标题观察断言为任务完成；技术主题本身含 AFK 标识或超时参数不属于活动统计话术。

#### Scenario: 完整的任务摘要
- **WHEN** 输入包含应用时长、AFK 覆盖及数据库/ETL 等标题事实
- **THEN** 所有摘要文案字段描述相关活动与主题，精确时长及覆盖仍位于结构化数据

#### Scenario: 常见统计话术被校验拒绝
- **WHEN** 任一摘要文案字段携带明确的 AFK 覆盖说明、活动时长播报或内部统计字段值
- **THEN** 本次响应不成为 SUMMARIZED，使用稳定且不含响应原文的校验错误进入既有失败重试路径；单次生成不额外调用模型进行修补

#### Scenario: 技术参数与历史兼容
- **WHEN** 摘要描述调试 AFK 采集器或排查 30 秒连接超时等真实主题，或读取已有旧摘要
- **THEN** 技术词和参数不被当作统计播报删除；旧摘要不因本次文案策略自动改写或重算

### Requirement: SPEC-WIKI-GEN-022 任务证据与断言强度
新生成任务 SHALL 包含非空、有效的事实引用与 observed/inferred 类型；应用列表和展示证据 SHALL 由校验后的引用在本地派生，不要求模型重复生成。模型携带的兼容 apps/evidence 字段 MUST NOT 改变派生输出；未知引用、错误类型的必需字段与超限引用 SHALL 被拒绝。系统 SHALL 按引用证据限制置信度，标题观察不得支持已完成、成功发布或已解决的成果断言。旧摘要缺少引用字段 SHALL 仍可读取为 legacy，不伪造历史引用。

#### Scenario: 安装器与主程序
- **WHEN** 任务引用分别来自安装器、主程序或不同协作程序，模型省略或混淆应用列表
- **THEN** 输出应用集合准确来自事实引用，不因重复生成的应用字段拒绝整份摘要，所有引用仍须真实有效

#### Scenario: 无效引用或跨任务应用
- **WHEN** 模型引用不存在或不属于当前输入的事实编号
- **THEN** 结果被拒绝，错误不回显活动正文，已有结果保持不变

#### Scenario: 只有观察的任务
- **WHEN** 一个任务只有未关联有效窗口的标题观察
- **THEN** 该任务不得保留 high 置信度或声称动作完成，引用在保存和重读后仍可追溯

#### Scenario: 标题字样不等于执行动作
- **WHEN** 文案明确描述带“添加群聊成员”字样的窗口标题、观察动词引导且以界面/窗口结尾的名称枚举、标题引用或局部否定语境
- **THEN** 不把该元描述误判为添加成员；其后独立的已执行/已完成断言仍按原规则拒绝

#### Scenario: 合法技术主题与统计播报
- **WHEN** 文案描述连接超时参数或调试 AFK 数据缺失，而非播报用户活动统计
- **THEN** 技术描述不被误拒，明确活动统计播报仍被拒绝

### Requirement: SPEC-WIKI-SRC-034 中文和时间覆盖质量
采样 SHALL 区分中文近重复与不同主题，保留长标题尾部区别信息，并在预算可容纳的候选组合中优先覆盖所有有事实的时间层。代表区间 SHALL 兼顾时间分布与最长有效活动，不仅按出现序号抽取。所有排序 SHALL 确定且不改变事实身份及完整指标。

窗口与额外上下文 SHALL 分别保留独立预算内可行的时间覆盖。纯观察记录的持续跨度 MUST NOT 被当作跨时段有效活动来提高采样优先级；排序 SHALL 按观察起点确定时间层，事实仍保留完整观察区间。

#### Scenario: 中文近重复
- **WHEN** 相同应用具有中文近重复安装文档及不同数据库主题
- **THEN** 近重复不当作完全独立主题挤掉所有不同主题，完整标题仍各自保留身份

#### Scenario: 大候选占预算
- **WHEN** 四个时段存在可放入预算的组合，但第一时段最高分候选会挤掉最后时段
- **THEN** 先保留可行全时段组合，再使用余量提高质量

#### Scenario: 长观察与确定活动
- **WHEN** 未匹配窗口的观察跨越多个时间层，且有已确认活动竞争有限预算
- **THEN** 长观察不得通过覆盖跨度获得持续活动优势，独立来源的可行活动覆盖保留

### Requirement: SPEC-WIKI-GEN-023 有界结构化分层汇总
超长摘要 SHALL 从裁剪后的完整白名单事实开始分块，分块之前不得先按单次采样预算丢弃后续事实。每块与最终汇总 SHALL 保留有效证据引用；指标 SHALL 由本地事实计算，不累加模型时长。生成 SHALL 具有总调用数、完整请求大小和总耗时上限，并复用每日预算控制。无法处理的事实 SHALL 显式报告省略，不宣称完整覆盖。

#### Scenario: 输入超过一次请求
- **WHEN** 完整事实超过一次请求预算且分层额度允许
- **THEN** 系统生成多个结构化中间结果后汇总，最终证据只指向原始允许事实，完整指标不变

#### Scenario: 上限与故障
- **WHEN** 达到调用数、时间或每日预算限制，或中间响应失败
- **THEN** 系统停止进一步调用，保留可重试状态或明确有界降级覆盖，不无限追加模型请求

### Requirement: SPEC-WIKI-GEN-024 中间结果复用与上层追溯
已验证的叶块、中间归并和最终结果 SHALL 持久化为有界派生检查点，以事实、稳定模型配置、提示词/算法/统计/日历版本隔离；进程重启和凭据轮换不得仅因进程内计数变化失去兼容结果。输入或语义配置变化 SHALL 隔离旧检查点，但 MUST NOT 重置该周期的累计预算。失败或未经校验的模型响应不得存为成功检查点；不保存完整prompt、API key或原始模型响应。周/月输入 SHALL 保留子周期日期、任务和来源标识，默认不自动重算旧摘要。

#### Scenario: 重试复用
- **WHEN** 最终汇总失败或应用退出后重试同一输入与模型配置
- **THEN** 已持久化的成功块复用，只请求缺失节点；失败块仍受同一累计预算约束

#### Scenario: 发布失败
- **WHEN** 最终结果已校验并保存为检查点但正式Wiki提交失败
- **THEN** 重试可从最终检查点恢复提交，不重复调用模型

#### Scenario: 输入或模型变化
- **WHEN** 事实、模型连接/名称、系统提示或生成算法版本改变
- **THEN** 不复用不兼容检查点，但相同周期已消耗的调用与token准入额度保留

#### Scenario: 多日同主题
- **WHEN** 多个日期涉及同一主题
- **THEN** 父级输入保留日期与来源，不把它们压成无日期字符串列表

### Requirement: SPEC-WIKI-GEN-025 周期累计准入与故障状态
系统 SHALL 按层级、周期起止与时区累计该逻辑摘要的调用和token准入消耗，跨重试、重启、模型/输入变化及执行日变化均不得自动归零。默认周期调用上限 SHALL 为12，token准入额度 SHALL 为256000；现有单棵树默认6次限制继续有效。发起请求前 SHALL 持久化预留；并发准入不得超过调用额度，真实usage或保守预留 SHALL 幂等结算。崩溃后的不确定请求不得无证据返还额度。

#### Scenario: 多次重试和重启
- **WHEN** 一个日摘要多次失败且进程重启
- **THEN** 已发出调用继续计入相同周期上限，不会每次重新获得12次额度

#### Scenario: 耗尽与显式调额
- **WHEN** 周期额度不足以发起下一请求
- **THEN** 暂停付费生成并显示稳定原因和消耗；次日不自动恢复，显式调高额度后可继续且不清零账本

#### Scenario: 故障分类
- **WHEN** 出现网络/超时、响应质量拒绝、配置不可用、准入阻断或发布失败
- **THEN** 对应状态可区分，付费失败消耗额度、预算阻断不增加普通失败重试，暂停条目不饿死其他可处理周期

### Requirement: SPEC-WIKI-SRC-035 主题组织与分阶段覆盖
系统 SHALL 基于完整允许事实规划主题候选、分块及合并预算。词面相似只作为组织候选的线索，不自动宣称同一真实任务。相同主题跨应用与时间段可归为一项任务；同应用不同主题以及与长任务同层的短独立主题 SHALL 保留可被选择的机会。输入、主题归属与展示证据覆盖 SHALL 分别报告，不能以引用数量代替语义质量。

#### Scenario: 跨应用同主题
- **WHEN** 同一项目的资料、编辑器和终端标题分布在不同应用与分块
- **THEN** 合并阶段获得可关联的主题卡片与来源，可以合并为任务而不是仅按应用列清单

#### Scenario: 根预算竞争
- **WHEN** 合并预算不足以重复展开全部事实区间
- **THEN** 优先压缩重复投影并以卡片为单位保留主题，不因移除一条展示引用而静默丢弃整项子任务；确实无法容纳的卡片有明确遗漏状态

#### Scenario: 不可行的完整计划
- **WHEN** 所有事实在给定调用与请求预算内无法全部处理
- **THEN** 从全局主题/时间/来源候选分配代表，不直接整块跳过并假称完整，输出真实覆盖和省略原因

### Requirement: SPEC-WIKI-SRC-036 噪声标题事实排除
新生成摘要的标题事实 SHALL 在采样和模型输入之前确定性地排除噪声事实。噪声 SHALL 至少包括：操作系统外壳界面（锁屏、开始菜单、系统搜索、快速设置、通知与系统托盘溢出、桌面外壳窗口，覆盖中文与英文系统标题）、浏览器中不携带页面主题的标题（新标签页、无标题页、无痕标签页入口、翻译提示及同类浏览器提示）以及 SelfAnalyst 自身的主程序窗口。安装器、卸载器以及标题中包含 SelfAnalyst 项目名称的其他应用窗口 MUST NOT 仅因名称而被视为噪声。噪声事实 MUST NOT 占用标题事实预算，也 MUST NOT 成为任务证据；活跃、AFK、应用耗时和切换次数等完整本地指标 MUST 与不排除噪声时一致。生成元数据 SHALL 报告被排除的噪声事实数量。噪声判定规则变化 SHALL 更新事实构建版本，并按既有规则隔离旧检查点。按 SPEC-DSUM-LIVE-002，看板当前窗消费的结构化标题事实 SHALL 与 Wiki 使用同一排除规则。

#### Scenario: 噪声不进入模型输入
- **WHEN** 一个小时内同时存在锁屏、系统搜索、浏览器新标签页、翻译提示、SelfAnalyst 主窗口以及若干有主题的文档与网页标题
- **THEN** 模型输入和任务证据只包含有主题的标题事实，生成元数据报告被排除的噪声数量

#### Scenario: 指标不因排除而改变
- **WHEN** 对同一周期分别在排除和不排除噪声的条件下构造事实
- **THEN** 活跃秒数、AFK 秒数、逐应用耗时、切换次数和 sourceCoverage 完全一致

#### Scenario: 安装器与项目页面不是噪声
- **WHEN** 周期内出现 SelfAnalyst 安装器、卸载器窗口，以及浏览器中 SelfAnalyst 仓库的发布页面
- **THEN** 这些事实保留为可用标题事实，只有 SelfAnalyst 主程序窗口被排除

#### Scenario: 噪声释放预算
- **WHEN** 噪声事实数量足以在不排除时挤占有主题事实的采样预算
- **THEN** 排除后被释放的预算可用于有主题的事实，采样仍满足确定性与时间覆盖契约

#### Scenario: 看板当前窗共享排除
- **WHEN** 看板当前窗同时出现锁屏界面和有主题的编辑器标题
- **THEN** 当前窗标题事实只包含编辑器标题并报告排除数量，应用耗时仍包含锁屏时长

### Requirement: SPEC-WIKI-WKR-024 无活动周期本地完成
当周期存在事件，排除噪声后没有候选标题事实，且没有子摘要时，worker SHALL 不调用模型，也 MUST NOT 预留或消耗该周期的调用与 token 准入额度。采样预算未选中、但候选事实仍然存在时 MUST NOT 视为无活动。该条目 SHALL 以本地生成的无任务结果完成：summary 与 primaryTask 使用固定的无活动描述，任务片段为空，并保留完整本地指标与 sourceCoverage，使父层级聚合的活跃、AFK 和应用耗时总量不变。本地完成的条目 MUST NOT 生成语义检索文档，也 MUST NOT 阻塞其他条目的语义文档发现。完全没有事件的周期继续按既有规则标记为 SKIPPED。

#### Scenario: 只有 AFK 的小时
- **WHEN** 一个已结束小时的活跃秒数为零、AFK 覆盖整小时且没有有效标题事实
- **THEN** 系统不发起模型请求，条目以本地无任务结果完成，AFK 秒数保留在该条目指标中

#### Scenario: 只有噪声的小时
- **WHEN** 一个小时有活跃时长，但全部标题事实都是噪声
- **THEN** 系统不发起模型请求，条目以本地无任务结果完成，活跃秒数和应用耗时仍计入该条目指标

#### Scenario: 父层级聚合
- **WHEN** 周周期的子日中包含本地完成的无活动条目
- **THEN** 周级将这些条目视为已完成依赖，聚合指标包含其本地指标，且不把固定无活动描述作为主题证据或子周期任务

#### Scenario: 不进入语义检索
- **WHEN** 本地完成的无活动条目早于其他尚未建立语义文档的已完成条目
- **THEN** 该条目不产生语义文档，后续已完成条目仍被发现并入队

### Requirement: SPEC-WIKI-GEN-026 最终主题排序与限量
新生成摘要的最终任务片段 SHALL 按本地权重降序排列：直接读取原始事件的层级（小时、半天、日）权重为主题成员事实的有效秒数之和（仅观察事实计为零），从子条目汇总的层级（周、双周、月）权重为主题成员事实的数量；权重相同时按确定性次序排列。最终片段 MUST 至多包含 5 个主题片段加 1 个“其他零散活动”片段。超出 5 个的主题 SHALL 在本地合并为“其他零散活动”片段：其证据引用取自被合并主题的代表引用且全部真实有效，置信度不高于被合并主题中的最低值，存在推断主题时类型为 inferred。该合并 MUST NOT 额外调用模型，被合并主题的成员事实 SHALL 仍可通过生成元数据追溯。primaryTask SHALL 取权重最高主题片段的标题；模型返回的 primaryTask 仍须满足结构和长度要求，但其叙述内容 MUST NOT 使响应失败。整体 summary 超过 300 字符时，系统 SHALL 以按权重排列的主要主题标题在本地组合替代，不因此判定响应失败。

#### Scenario: 长尾主题被合并
- **WHEN** 最终主题卡片有 12 张
- **THEN** 输出 5 个按权重排序的主题片段和 1 个“其他零散活动”片段，后者的引用均来自被合并主题且可在本地追溯

#### Scenario: 主要任务反映有效时长
- **WHEN** 沟通类主题的成员事实有效秒数占小时活跃时长的多数，而浏览类主题拥有更多不同标题
- **THEN** 沟通类主题排在首位，primaryTask 等于该主题标题

#### Scenario: 被忽略的模型主要任务
- **WHEN** 模型返回的 primaryTask 含统计播报，但主题卡片与整体 summary 有效
- **THEN** 条目仍成为 SUMMARIZED，primaryTask 为权重最高的主题标题，不追加模型调用

#### Scenario: 主题数量不超过上限
- **WHEN** 最终主题卡片不超过 5 张
- **THEN** 输出按权重排序的全部主题，不生成“其他零散活动”片段

#### Scenario: 过长整体摘要
- **WHEN** 模型返回的整体 summary 超过 300 字符但其余字段有效
- **THEN** 条目仍成为 SUMMARIZED，summary 为本地组合的主要主题描述，且不追加模型调用

#### Scenario: 父层级权重
- **WHEN** 周级摘要的主题来自多个子日的任务事实
- **THEN** 主题按成员事实数量排序，排序结果在相同输入下确定

### Requirement: SPEC-WIKI-GEN-027 证据边界声明清理
新生成摘要的提示词 MUST NOT 以可被复述的否定清单描述证据边界，而 SHALL 以正向措辞要求使用查看、涉及、相关等有限描述。保存前，系统 SHALL 从 summary 和任务片段 summary 中删除只用于声明证据边界的分句（例如声明标题仅表明、不证明、不表示或不代表运行、配置、交付、发送消息、参会或完成），并在生成元数据中报告受影响的句子数量。处于否定声明作用范围内的完成类字样（如“不证明具体任务已完成”）SHALL 按声明删除；由转折词引出的成果断言（如“但已解决问题”）MUST 保留并交由既有断言规则判定。同一句中声明前的主题描述 SHALL 保留。删除后字段为空时，系统 SHALL 以相应主题标题在本地组合替代。清理 MUST NOT 删除描述真实技术主题的否定表达，也 MUST NOT 放宽已完成、已发布、已解决等无证据成果断言的拒绝规则；清理本身 MUST NOT 使响应失败或触发额外模型调用。

#### Scenario: 尾部免责声明被删除
- **WHEN** 整体 summary 以“以上仅为查看或窗口标题，不证明项目运行、配置、交付或消息发送。”结尾
- **THEN** 保存的 summary 不含该句，其余主题描述保持不变，生成元数据记录一次清理

#### Scenario: 片段内的边界子句
- **WHEN** 任务片段 summary 为“涉及企业微信与 WorkBuddy 窗口；不表明发送消息或参会。”
- **THEN** 保存的片段 summary 为“涉及企业微信与 WorkBuddy 窗口。”

#### Scenario: 否定范围内的完成字样
- **WHEN** summary 含“标题仅表明相关开发活动被观察，不证明具体任务已完成或交付。”，或片段 summary 为“涉及数据库同步，不证明已交付”
- **THEN** 前者整句被删除；后者保存为“涉及数据库同步。”

#### Scenario: 转折后的成果断言
- **WHEN** 文案为“不证明实际参加会议，但已解决问题”或“不证明参会但已解决问题”
- **THEN** 声明部分被删除，保留的“已解决问题”仍按既有断言强度规则被拒绝

#### Scenario: 清理后为空
- **WHEN** 任务片段 summary 只包含证据边界声明
- **THEN** 片段 summary 由该片段标题在本地组合生成，条目仍成为 SUMMARIZED

#### Scenario: 技术主题与成果断言
- **WHEN** 文案描述“排查连接不返回数据的问题”，或声称“已完成发布”
- **THEN** 前者不被当作声明删除；后者仍按既有断言强度规则被拒绝

### Requirement: SPEC-WIKI-GEN-028 上层摘要抽象
HALF_DAY、DAY、WEEK、BIWEEK 和 MONTH 的新生成摘要 SHALL 用一句主线描述该周期；模型返回多句时，本地 MUST 只保留第一句，第一句为空时 SHALL 由保留的主题标题在本地组合一句。最终主题 MUST 不超过 3 个。超出的主题 SHALL 在本地合并为“其余活动”，其描述不得逐条列出被合并的标题。每个子片段进入上层时 SHALL 带有该子周期的有效秒数，上层排序 MUST 使用这些秒数。HOUR 摘要不受单句约束。已有摘要 MUST NOT 因本次调整自动重算。

#### Scenario: 半天不复述每个小时
- **WHEN** 一个半天的小时摘要包含 6 个不同主题
- **THEN** 半天摘要最多 3 个主题加一条“其余活动”，主线不是这些小时标题的清单

#### Scenario: 用时决定上层顺序
- **WHEN** 一个子小时的有效秒数高于其他子小时
- **THEN** 该小时的首个主题在上层排序中权重更高

#### Scenario: 上层摘要只保留一句
- **WHEN** 模型为日摘要返回“上午处理订单模块。下午参加评审。”
- **THEN** 保存的日摘要为“上午处理订单模块。”，小时摘要的多句文案保持不变

### Requirement: SPEC-WIKI-PRV-020 摘要输入脱敏与排除
新生成摘要在形成标题事实之前 SHALL 脱敏内网 IP、会议号和账号验证页标题。会议号 SHALL 覆盖“会议号”“会议 ID”“Meeting ID”后跟随的号码，冒号可有可无，号码可用空格或短横分隔。标题包含无痕、隐身、InPrivate、Incognito、隐私浏览或 Private Browsing 时，该活动 MUST NOT 成为标题事实；系统 MUST NOT 声称能识别标题中没有这些标记的无痕窗口。配置的排除应用（按可执行文件名，不区分大小写）和排除网站（按标题关键词，不区分大小写）MUST NOT 成为标题事实，排除应用的名称 MUST NOT 出现在发给模型的应用权重中。

上层周期读取子摘要时，任务标题与描述 SHALL 经过同样的脱敏；被排除的应用 SHALL 从子片段的应用列表中移除，应用全部被排除或标题命中无痕标记、排除网站的子片段 MUST NOT 进入上层输入。活跃时长、AFK 和应用耗时等本地统计 MUST 保持不变。看板当前窗 SHALL 使用同一策略。

#### Scenario: 敏感标题被替换
- **WHEN** 窗口标题包含内网 IP、“会议号：123 456 789”、“Meeting ID 123-456-789”或账号验证页
- **THEN** 模型输入不再包含原 IP、会议号或验证页标题

#### Scenario: 无痕活动不进入摘要
- **WHEN** 窗口标题标明无痕、隐身或 InPrivate
- **THEN** 这些标题不进入模型输入

#### Scenario: 按应用和网站排除
- **WHEN** 用户配置排除了某个应用，或配置了出现在页面标题中的网站关键词
- **THEN** 匹配的标题不进入模型输入，排除应用的名称不出现在提示词的应用权重中，该应用的耗时仍保留在本地统计中

#### Scenario: 旧子摘要中的敏感内容
- **WHEN** 升级前生成的小时摘要的任务标题或描述含有内网 IP、会议号，或只来自被排除的应用
- **THEN** 半天摘要的模型输入不包含原 IP 和会议号，也不包含只来自被排除应用的子片段

### Requirement: SPEC-WIKI-GEN-029 置信度不跟随覆盖完整性
新生成任务的 confidence SHALL 只反映证据类型。只有标题观察的任务 MUST 为 low。推断任务 MUST NOT 为 high。AFK、覆盖缺口或冲突秒数 MUST NOT 降低任务置信度；这些状态 SHALL 继续只出现在 sourceCoverage 和内部统计中。

#### Scenario: 覆盖不完整仍保留证据置信度
- **WHEN** AFK 覆盖为 partial、missing、failed 或 lagging，且模型给出的任务证据不是纯观察
- **THEN** 任务置信度保持模型给出的 high 或 medium，覆盖状态仍可在 sourceCoverage 中读取

#### Scenario: 只有观察
- **WHEN** 任务引用的事实都是未匹配有效窗口的标题观察
- **THEN** 该任务置信度为 low
