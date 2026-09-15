## MODIFIED Requirements

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

### Requirement: SPEC-WIKI-SRC-001..006、010、011、013 标题事实来源与预算
事实 SHALL 来自当前主机由永久原始事件投影生成的 window、AFK 和 content bucket；Wiki MUST NOT 直接查询逐 heartbeat 原始事件层。窗口事件 SHALL 用于应用耗时、切换次数和窗口标题样本；AFK 用于非活跃时间及逐应用有效时长扣除；content 只提供 app、系统标题、可选上下文标题和种类。任一投影 bucket 缺失或查询失败时，其他可用事实 MAY 继续生成。单次 prompt 输入 SHALL 受 `wiki.prompt.maxContentChars` 限制，单个窗口标题样本默认最多 160 字符；上下文标题 SHALL 按 app、effectiveTitle、contextKind 去重。Wiki MUST NOT 读取 `text_content`。

#### Scenario: content bucket 含旧正文
- **WHEN** 内容事件投影同时具有标题字段和旧 `text_content`
- **THEN** Wiki facts 只使用标题投影，prompt 和数据库不包含旧正文

#### Scenario: 部分 bucket 缺失
- **WHEN** AFK 或 content 投影 bucket 不存在，但 window 投影 bucket 有可用事件
- **THEN** 系统使用可用指标继续构造 facts，缺失指标置零或为空并明确覆盖不足；AFK 缺失时窗口耗时只能作为未扣除非活跃时间的估计

#### Scenario: Wiki 不读取永久原始层
- **WHEN** Wiki 生成小时、半天或天级事实
- **THEN** 系统读取事件投影且不调用桌面原始事件查询 API

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

## ADDED Requirements

### Requirement: SPEC-WIKI-STAT-020 共享有效活动事实
Wiki 应用耗时、活跃时长、非活跃时长及未知活动 SHALL 遵循 activity-statistics 契约。标题事实仍 SHALL 仅来自允许的事件投影。AFK 覆盖缺失 SHALL 在事实与生成文案中反映，不得以零值暗示已确认无非活跃时间。高层级 SHALL 仅聚合当前口径、边界一致的下级指标，并保留未知活动和覆盖信息。

#### Scenario: Wiki 与桌面一致
- **WHEN** Wiki 与桌面查询同一主机、时区及时间区间的同一事件快照
- **THEN** 二者在展示格式化前具有一致的应用有效时长、未知活动与 AFK 指标

### Requirement: SPEC-WIKI-STAT-021 历史统计版本隔离与重算
Wiki SHALL 区分事实统计和周期边界版本。旧版或缺少版本的条目 MUST NOT 作为新口径桌面摘要、父级依赖或默认 Wiki 检索的权威结果；旧条目 SHALL 保留用于追溯。后台 SHALL 按新边界发现历史事实并可恢复、幂等地重算受影响周期，父级等待当前版本依赖，受影响语义索引同步隔离或更新。此过程 MUST NOT 阻塞启动或看板，MUST NOT 改写永久原始事件，且 SHALL 遵守既有 LLM 预算及重试策略。

#### Scenario: 已有午夜日摘要
- **WHEN** 系统发现旧午夜边界的 SUMMARIZED DAY 及依赖它的周摘要
- **THEN** 旧结果不进入新口径结果，后台按 04:00 周期构建新结果，等待期间看板使用明确来源的本地事实

#### Scenario: 重算中断
- **WHEN** 历史重算期间程序重启或 LLM 不可用
- **THEN** 已完成的新版本结果保留，未完成工作可恢复且不重复生成同一当前版本周期
