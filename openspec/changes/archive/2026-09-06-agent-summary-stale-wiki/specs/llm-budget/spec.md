## MODIFIED Requirements

### Requirement: SPEC-BUDGET-KNOB-001、002 结构性限流
Agent SHALL 以 llm.agent.maxIters 作为最大 reasoning/tool 轮数。桌面 summary 的现场 LLM 增强 SHALL 只作用于未闭合当前窗条目，且此类增强次数不得超过 `desktop.summary.maxTimelineLlm`；已结束时段的 Wiki 摘要不计入该上限，也 MUST NOT 因此再次调用 LLM。0 SHALL 禁用当前窗现场 LLM 增强，但不阻止读取已有 Wiki 摘要或快照。

#### Scenario: timeline 增强上限
- **WHEN** summary 有 20 条 timeline 且上限为 4，其中仅 2 条属于未闭合当前窗
- **THEN** 现场 LLM 最多只增强这 2 条当前窗，已结束 Wiki 条目不发起 LLM 也不计入上限

#### Scenario: 关闭当前窗 LLM
- **WHEN** `desktop.summary.maxTimelineLlm` 为 0
- **THEN** 当前窗使用本地事实或快照文案，已结束 Wiki 摘要仍可展示
