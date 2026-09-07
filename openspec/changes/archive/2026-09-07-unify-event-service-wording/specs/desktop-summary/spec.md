## MODIFIED Requirements

### Requirement: SPEC-DSUM-WIKI-001 已结束时段复用 Wiki
已结束时段的 headline、insight 与摘要文案 SHALL 优先来自 `llm-wiki.db` 中对应层级的 `SUMMARIZED` 条目。Wiki 为桌面时间轴上这些时段的摘要权威；事件 summary bucket 投影若存在，MUST NOT 取代 Wiki 权威。

#### Scenario: 昨天已有 Wiki 日摘要
- **WHEN** 昨天存在 `SUMMARIZED` 的 DAY 条目
- **THEN** 时间轴昨天条目使用该 Wiki 摘要，而不是现场生成新文案

#### Scenario: 高层级已完成
- **WHEN** 本周存在 `SUMMARIZED` 的 WEEK 条目
- **THEN** 时间轴本周条目使用该 Wiki 摘要
