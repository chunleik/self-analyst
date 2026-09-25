## ADDED Requirements

### Requirement: SPEC-WIKI-GEN-029 置信度不跟随覆盖完整性
新生成任务的 confidence SHALL 只反映证据类型。只有标题观察的任务 MUST 为 low。推断任务 MUST NOT 为 high。AFK、覆盖缺口或冲突秒数 MUST NOT 降低任务置信度；这些状态 SHALL 继续只出现在 sourceCoverage 和内部统计中。

#### Scenario: 覆盖不完整仍保留证据置信度
- **WHEN** AFK 覆盖为 partial、missing、failed 或 lagging，且模型给出的任务证据不是纯观察
- **THEN** 任务置信度保持模型给出的 high 或 medium，覆盖状态仍可在 sourceCoverage 中读取

#### Scenario: 只有观察
- **WHEN** 任务引用的事实都是未匹配有效窗口的标题观察
- **THEN** 该任务置信度为 low
