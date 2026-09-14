## MODIFIED Requirements

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

### Requirement: SPEC-ADV-API-001..004 summary API
`GET /desktop/summary` SHALL 包含 behaviorAdvice 字段；生成失败 MUST NOT 使 current 或 timeline 失败，
而 SHALL 返回本地建议或 empty。前端 SHALL 兼容旧响应中的 behaviorAdvice=null，且不展示建议卡片或建议空状态。

#### Scenario: 建议生成抛错
- **WHEN** behavior advice 构建失败但当前状态和时间线可用
- **THEN** summary 响应仍返回 current/timeline，并提供 empty 或 null-compatible 建议状态

### Requirement: SPEC-ADV-ERR-001..005 错误隔离与安全渲染
LLM、AW bucket 或 Wiki 摘要缺失 MUST NOT 使 看板失败。其他消费建议的前端 SHALL 对建议文本做 HTML 转义；
建议生成或渲染错误 MUST NOT 影响摘要当前状态数据和时间轴。

#### Scenario: 不可信建议文本
- **WHEN** 建议 title/body/evidence 含 HTML 或脚本
- **THEN** 看板不渲染这些建议字段；其他消费建议的界面按文本转义显示，不执行标记

### Requirement: SPEC-ADV-NON-001..006 功能边界
行为建议 MUST NOT 承担目标管理、模式确认、改进记录、系统通知或托盘提醒。后端 SHALL 一次最多返回一条最重要建议，不提供点赞、点踩或在线训练模型功能。

#### Scenario: 多条候选建议
- **WHEN** 生成过程得到多个候选
- **THEN** 后端只选择一条最高优先级建议返回，看板不展示该建议

## REMOVED Requirements

### Requirement: SPEC-ADV-IA-001..004 信息架构
**Reason**: 看板正文改为仅显示时间轴，取消独立建议区域。
**Migration**: 页面布局遵循 SPEC-DSUM-UI-002；后端建议生成和响应字段继续保留。

### Requirement: SPEC-ADV-UI-001..006 卡片呈现与只读边界
**Reason**: 看板移除建议卡片及其加载、空状态和视觉呈现。
**Migration**: 不新增替代卡片或入口；建议只读边界继续由 SPEC-ADV-GOAL-001..005 约束。
