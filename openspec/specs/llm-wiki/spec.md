# LLM Wiki 规格

## Purpose

定义本地多层时间摘要、历史发现与重试、Agent 时间查询、标题事实隐私边界，以及可选语义索引和 embedding 检索行为，使长期复盘不依赖原始屏幕正文。

## Requirements

### Requirement: SPEC-WIKI-GOAL-001..008 多级摘要与检索目标
系统 SHALL 支持 HOUR、HALF_DAY、DAY、WEEK、BIWEEK 和 MONTH 层级，并默认永久保存已生成摘要。
HOUR/HALF_DAY/DAY SHALL 从原始窗口、AFK 和内容标题事实生成；WEEK/BIWEEK/MONTH SHALL 从已完成
的下级摘要生成。Agent SHALL 能按时间范围查询摘要；可选语义检索 SHALL 能按模糊主题检索摘要与
任务片段。后台发现和补算 MUST NOT 阻塞应用启动。LLM 不可用时 MUST 保留可重试状态，不写规则兜底
伪摘要。Wiki 数据 MUST NOT 长期保存完整 OCR/UIA 正文。

#### Scenario: 低层级生成
- **WHEN** 已结束小时、半天或统计日具有可用标题事实
- **THEN** 系统从该范围原始事实生成对应层级摘要

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
HOUR、HALF_DAY 和 DAY SHALL 直接聚合其时间范围内的原始事实，不得由小时或半天摘要拼接。
WEEK SHALL 只聚合该周 SUMMARIZED DAY；BIWEEK SHALL 聚合两个连续 SUMMARIZED WEEK；MONTH SHALL
聚合同一统计月 SUMMARIZED DAY。预期子时间块未全部达到 SUMMARIZED 或 SKIPPED 时，父级 SHALL
保持 PENDING。

#### Scenario: DAY 不拼接下级摘要
- **WHEN** 同一天已存在 HOUR 或 HALF_DAY 摘要
- **THEN** DAY 仍从该日原始事实构建，不拼接这些摘要

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
事实 SHALL 来自当前主机的合并 window、AFK 和 content bucket；Wiki MUST NOT 直接查询逐 heartbeat 原始事件层。窗口事件 SHALL 用于应用耗时、切换次数和窗口标题样本；AFK 用于非活跃时间及逐应用有效时长扣除；content 只提供 app、系统标题、可选上下文标题和种类。任一投影 bucket 缺失或查询失败时，其他可用事实 MAY 继续生成。单次 prompt 输入 SHALL 受 `wiki.prompt.maxContentChars` 限制，单个窗口标题样本默认最多 160 字符；上下文标题 SHALL 按 app、effectiveTitle、contextKind 去重。Wiki MUST NOT 读取 `text_content`。

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
事实采样 SHALL 不改变活跃、AFK、应用耗时及切换次数等完整本地指标；每个摘要仍 SHALL 最多调用一次模型。结构化事实 SHALL 仅包含既有标题事实白名单，不读取正文或文件内容。新事实和提示词 SHALL 记录更新版本，既有已完成摘要 SHALL 保留且不因本次采样调整自动触发付费重算。

#### Scenario: 采样预算不同
- **WHEN** 同一活动区间使用不同标题预算生成摘要
- **THEN** 选中事实可以不同，但完整本地指标完全一致，模型调用次数不增加

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
系统 SHALL 使用含事实 ID、来源、应用、标题、类型、有效秒数、出现次数及有界代表区间的紧凑投影作为模型输入，并说明时间表示、观察标记和省略语义。本地事实对象 SHALL 保留有界来源事件引用及原始精度时间，模型输入不要求重复展开这些引用。`wiki.prompt.maxContentChars` 默认 SHALL 为 24000 字符，显式合法配置 SHALL 继续生效；配置、模板及实际运行默认值 SHALL 一致。扩大预算 MUST NOT 增加每份摘要的模型调用次数或自动重算已有摘要。

#### Scenario: 同一预算的紧凑表示
- **WHEN** 多个短标题有若干时间区间及较长来源标识
- **THEN** 模型输入省去展开的来源标识且仍可通过事实 ID 关联本地来源，序列化字符数不超过配置预算

#### Scenario: 默认与显式配置
- **WHEN** 未配置标题事实预算或使用无效的过小值
- **THEN** 使用 24000 字符默认值，配置模板一致；用户显式合法设置 12000 时仍使用 12000

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
