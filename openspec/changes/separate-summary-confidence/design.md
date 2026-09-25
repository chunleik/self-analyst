## Context

见 proposal.md。原先 `uncertainActivity` 把 AFK 不完整直接写成置信度上限。

## Goals / Non-Goals

**Goals:**

- 置信度只表达证据类型。

**Non-Goals:**

- 不隐藏界面上的置信度字段。
- 不把观察证据提升为 high。

## Decisions

解析时忽略覆盖标志。提示词不再给出由覆盖率决定的 taskConfidenceCeiling。

## Risks / Trade-offs

覆盖很差时，文案仍可能显示 high。用户需要同时看 sourceCoverage 才知道时间统计不完整。
