## MODIFIED Requirements

### Requirement: SPEC-DSUM-FALL-001 Wiki 缺失时的降级
已结束时段在 Wiki 为缺失、PENDING、FAILED 或 SKIPPED 时，SHALL 使用本地事实填充该条目且不得为补齐该时段等待或调用 LLM。已结束 DAY/HALF_DAY 存在已完成子摘要时 SHALL 在默认折叠状态按时间顺序完整展示互不重叠的子摘要原文和对应范围；已完成半天 SHALL 优先于其小时摘要。该结果 SHALL 标记为部分摘要和不完整，不作为完整日摘要保存。无可用子摘要时 SHALL 明确标记仅应用统计。跨度条目在父级未完成时 SHALL 拼装可用子级 SUMMARIZED 条目与当前窗；不得把部分结果描述为完整 Wiki 历史。Wiki 整体不可用 MUST NOT 使当前窗或建议失败。完整父摘要就绪后 SHALL 替换部分结果。

#### Scenario: 昨天 Wiki 仍为 PENDING
- **WHEN** 昨天 DAY 条目状态为 PENDING
- **THEN** 时间轴昨天条目显示可用子摘要和本地事实并标记不完整，接口不等待该 Wiki 生成完成

#### Scenario: Wiki 已关闭
- **WHEN** wiki.enabled=false 或 Wiki store 不可用
- **THEN** 已结束时段显示明确标记的应用统计及不完整状态，当前窗与建议仍可返回

#### Scenario: 单个小时失败
- **WHEN** 上午半天已完成，下午有已完成小时及失败小时，日摘要等待依赖
- **THEN** 展示上午半天与下午已完成小时的原文和范围，不重复展示上午小时，不填补失败小时，不混入今天事实

#### Scenario: 父摘要完成与边界隔离
- **WHEN** 同时存在其他时区、越界子摘要或后来完成的完整日摘要
- **THEN** 不采用其他时区或非精确子周期的摘要；完整日摘要就绪后优先展示完整结果且清除旧部分状态

## ADDED Requirements

### Requirement: SPEC-DSUM-FALL-002 历史摘要依赖诊断
已结束 DAY/HALF_DAY 缺少完整摘要时，响应和时间轴 SHALL 区分等待生成、等待下级、失败、跳过、缺失及不可用。等待下级时 SHALL 显示实际未完成的下级周期范围和已保存的安全原因、调用/token 额度与重试时间；不得将下级预算消耗冒充父周期消耗。未完成状态 SHALL 在折叠状态可见，完整原因和子摘要的主题明细 SHALL 在详情可读。展示 MUST NOT 包含完整 prompt、失败响应、配置或私密字段，MUST NOT 触发模型调用或改写 Wiki 状态。

#### Scenario: 日摘要间接等待失败小时
- **WHEN** 日和后半天为 PENDING，后半天的一个小时因质量校验 FAILED
- **THEN** 日条目标记部分或仅统计、等待下级，详情列出失败小时范围、质量原因及该小时的已用额度和重试时间

#### Scenario: 尚未开始与无记录
- **WHEN** 子周期均已完成但父摘要尚未生成，或子周期缺失／跳过
- **THEN** 分别准确显示等待生成、缺失或跳过；不虚构失败原因、重试时间或主题

#### Scenario: 旧摘要与安全显示
- **WHEN** 旧数据没有生成元数据，或子摘要包含 HTML 字符
- **THEN** 使用已有状态给出可读说明，文案作为文本转义显示，其他条目与继续追问可用
