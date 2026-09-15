# 行为建议与鼓励规格

## Purpose

定义后端基于本地行为事实生成单条鼓励、建议或提醒的结构、证据、降级、只读边界和隐私约束。看板不展示独立建议卡片，摘要接口继续保留建议数据。

## Requirements

### Requirement: SPEC-ADV-GOAL-001..005 单条可解释建议
后端 SHALL 一次最多返回一条 encouragement、suggestion 或 reminder；看板不展示独立行为建议区域。
非 empty 建议 MUST 包含可解释证据和依据摘要；数据不足或生成不可用时 SHALL 返回明确 empty 状态，
MUST NOT 编造结论。建议 SHALL 为只读，不自动写长期记忆、创建任务或确认行为模式。

#### Scenario: 有充分行为事实
- **WHEN** 系统生成一条具有有效证据的建议
- **THEN** 响应提供类型、范围、结论、说明、证据标签和依据摘要，看板不展示建议卡片

#### Scenario: 数据不足
- **WHEN** 本地事实不足以支持可靠建议
- **THEN** 返回 empty 状态，看板不展示建议空状态，不显示虚假证据

### Requirement: SPEC-ADV-MDL-001..003 BehaviorAdvice 模型
BehaviorAdvice SHALL 提供 type、scopeLabel、generatedAt、title、body、evidenceTags、basis、confidence
和可选 emptyReason。type SHALL 为 encouragement/suggestion/reminder/empty；confidence SHALL 为
high/medium/low。非 empty title/body MUST 非空，evidenceTags SHALL 为 1..5 个；empty MAY 没有证据，
且 emptyReason 仅在 empty 时出现。basis SHALL 表达观察范围、趋势、建议种类和数据完整度。

#### Scenario: 非 empty 数据
- **WHEN** 后端返回 suggestion
- **THEN** title/body 非空、至少一个证据标签、confidence 与 basis 值合法

#### Scenario: empty 数据
- **WHEN** type=empty
- **THEN** 响应可使用 emptyReason 表达空状态原因，不要求证据标签；看板不展示对应空状态

### Requirement: SPEC-ADV-SRC-001、003、004 行为事实来源
建议 MAY 使用窗口、AFK、任务摘要和 Wiki 摘要，以及内容事件允许的窗口/上下文标题与统计指标。
系统 MUST NOT 把完整 OCR/UIA 正文作为建议或 LLM 输入。发送给 LLM 的事实摘要 SHALL 有硬长度上限；
证据标签和依据 MUST 由裁剪后的可解释事实构造。

#### Scenario: 旧内容事件含正文
- **WHEN** 上游事件含历史 text_content 但也有标题事实
- **THEN** 建议输入只使用允许标题与指标，输出不包含完整正文

### Requirement: SPEC-ADV-GEN-001..006 生成与本地降级
需要生成新建议时，每次 SHALL 最多生成一条最重要建议。若摘要快照仍有效且当前窗事实未触发失效，系统 SHALL 复用快照中的建议，MUST NOT 仅因页面重开、看板切回或定时刷新而重新生成。LLM 可用时 MAY 负责自然语言措辞，但 MUST NOT 覆盖本地计算指标；LLM 不可用或失败时 SHALL 使用本地规则生成朴素建议，无法形成可靠规则时返回 empty。本地规则 SHOULD 覆盖分心时长下降、窗口切换显著上升和连续晚间娱乐偏高等场景。

#### Scenario: LLM 改写事实
- **WHEN** 模型返回与本地统计冲突的数值或趋势
- **THEN** 系统保留本地事实，并仅采用安全可解释的措辞

#### Scenario: LLM 不可用
- **WHEN** 模型未配置、预算阻断或调用失败
- **THEN** 后端返回本地建议或 empty，summary 主响应继续成功

#### Scenario: 快照建议仍有效
- **WHEN** 客户端再次请求 summary 且快照建议未因当前窗事实变化失效
- **THEN** 响应复用快照中的 behaviorAdvice，不发起新的建议生成

### Requirement: SPEC-ADV-API-001..004 summary API
`GET /desktop/summary` SHALL 包含 behaviorAdvice 字段；生成失败 MUST NOT 使 current 或 timeline 失败，
而 SHALL 返回本地建议或 empty。前端 SHALL 兼容旧响应中的 behaviorAdvice=null，且不展示建议卡片或建议空状态。

#### Scenario: 建议生成抛错
- **WHEN** behavior advice 构建失败但当前状态和时间线可用
- **THEN** summary 响应仍返回 current/timeline，并提供 empty 或 null-compatible 建议状态

### Requirement: SPEC-ADV-ERR-001..005 错误隔离与安全渲染
LLM、AW bucket 或 Wiki 摘要缺失 MUST NOT 使看板失败。其他消费建议的前端 SHALL 对建议文本做 HTML 转义；
建议生成或渲染错误 MUST NOT 影响摘要当前状态数据和时间轴。

#### Scenario: 不可信建议文本
- **WHEN** 建议 title/body/evidence 含 HTML 或脚本
- **THEN** 看板不渲染这些建议字段；其他消费建议的界面按文本转义显示，不执行标记

### Requirement: SPEC-ADV-PRV-001..004 隐私边界
卡片、prompt 和日志 MUST NOT 包含完整 OCR/UIA 原文、API key、base URL、完整用户配置或完整 prompt。
LLM 输入 SHALL 只使用有界事实摘要；日志只能记录受限状态和错误。

#### Scenario: 输入含敏感配置
- **WHEN** 上游 context 意外包含 API key 或配置 URL
- **THEN** 建议 prompt 和卡片不包含这些值

### Requirement: SPEC-ADV-NON-001..006 功能边界
行为建议 MUST NOT 承担目标管理、模式确认、改进记录、系统通知或托盘提醒。后端 SHALL 一次最多返回一条最重要建议，不提供点赞、点踩或在线训练模型功能。

#### Scenario: 多条候选建议
- **WHEN** 生成过程得到多个候选
- **THEN** 后端只选择一条最高优先级建议返回，看板不展示该建议

### Requirement: SPEC-ADV-STAT-001 统计日与有效耗时
行为建议的按日分组及日均值 SHALL 使用本地 04:00 统计日；应用、娱乐及晚间耗时 SHALL 按 activity-statistics 契约裁剪和排除 AFK。晚间娱乐 SHALL 统计本地 22:00 至次日 04:00 的有效娱乐活动，并归属开始日。未知应用 MUST NOT 被推断为娱乐或生产力应用；覆盖不足时 SHALL 报告依据不足，无法形成可靠比较时返回 empty。

#### Scenario: 凌晨娱乐
- **WHEN** 用户在凌晨 01:00 至 02:00 使用娱乐应用，其中半小时为 AFK
- **THEN** 前一统计日获得半小时有效晚间娱乐时长

#### Scenario: 不完整比较
- **WHEN** AFK 查询失败导致近期与基准时段无法可靠比较
- **THEN** 建议不将未扣除 AFK 的差异描述为已确认行为趋势
