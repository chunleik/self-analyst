## ADDED Requirements

### Requirement: SPEC-WIKI-DSK-001 桌面时间轴消费已结束 Wiki
桌面时间轴对已结束时段 SHALL 以 `llm-wiki.db` 中对应层级的 `SUMMARIZED` 条目为摘要权威，MUST NOT 为这些时段再次现场生成 LLM 文案。父级跨度未完成时 SHALL 拼装可用子级 `SUMMARIZED` 条目；PENDING、FAILED、SKIPPED 或缺失 MUST NOT 阻塞当前窗。该消费路径 MUST NOT 改变 Wiki worker 的发现、生成或补算职责，也不得把 ActivityWatch summary bucket 投影当作比 Wiki 更高的权威。

#### Scenario: 已结束日复用 Wiki
- **WHEN** 昨天存在 SUMMARIZED DAY 条目且客户端请求桌面 summary
- **THEN** 时间轴昨天条目使用该 Wiki 摘要，不对该日发起新的 summary LLM 调用

#### Scenario: 未完成跨度不阻塞页面
- **WHEN** 本周 WEEK 条目仍为 PENDING 但若干已结束 DAY 已 SUMMARIZED
- **THEN** 时间轴使用这些 DAY 摘要与当前窗拼装本周，并继续返回当前窗
