## MODIFIED Requirements

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

## ADDED Requirements

### Requirement: SPEC-WIKI-GEN-028 上层摘要抽象
HALF_DAY、DAY、WEEK、BIWEEK 和 MONTH 的新生成摘要 SHALL 用一句主线描述该周期，最终主题 MUST 不超过 3 个。超出的主题 SHALL 在本地合并为“其余活动”，其描述不得逐条列出被合并的标题。每个子片段进入上层时 SHALL 带有该子周期的有效秒数，上层排序 MUST 使用这些秒数。已有摘要 MUST NOT 因本次调整自动重算。

#### Scenario: 半天不复述每个小时
- **WHEN** 一个半天的小时摘要包含 6 个不同主题
- **THEN** 半天摘要最多 3 个主题加一条“其余活动”，主线不是这些小时标题的清单

#### Scenario: 用时决定上层顺序
- **WHEN** 一个子小时的有效秒数高于其他子小时
- **THEN** 该小时的首个主题在上层排序中权重更高
