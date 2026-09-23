## MODIFIED Requirements

### Requirement: SPEC-WIKI-STAT-020 共享有效活动事实
Wiki 应用耗时、活跃时长、非活跃时长及未知活动 SHALL 遵循 activity-statistics 契约。标题事实仍 SHALL 仅来自允许的事件投影。AFK 覆盖缺失 SHALL 在结构化事实、指标及来源覆盖中反映，不得以零值暗示已确认无非活跃时间；这些统计只用于任务优先级与置信度判断，MUST NOT 要求在 Wiki 自然语言摘要中复述。高层级 SHALL 仅聚合当前口径、边界一致的下级指标，并保留未知活动和覆盖信息。

#### Scenario: Wiki 与桌面一致
- **WHEN** Wiki 与桌面查询同一主机、时区及时间区间的同一事件快照
- **THEN** 二者在展示格式化前具有一致的应用有效时长、未知活动与 AFK 指标

#### Scenario: 覆盖不足仍保留统计
- **WHEN** AFK 来源缺失、不完整或存在未覆盖活动
- **THEN** 结构化覆盖与精确指标保持可用，任务文案采用谨慎措辞，任务片段置信度最高为 medium，不解释 AFK 统计原因

## ADDED Requirements

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
