## 1. 回归测试与实现

- [x] 1.1 在 `self-analyst-app/src/test/java/com/selfanalyst/agent/` 增加同一提示词连续两轮使用不同时间的回归测试，先运行针对性测试确认旧实现无法满足。
- [x] 1.2 修改 `AgentPrompts`、`DynamicMemoryContextMiddleware` 和 `SelfAnalystAgent`，使系统提示词在每轮填入本地时间，并运行相关 Agent 测试。

## 2. 规格与验证

- [x] 2.1 将时间刷新契约同步到 `openspec/specs/internationalization/spec.md`，运行 `openspec validate fix-agent-current-time --strict`。
- [x] 2.2 运行适当范围的 Maven 测试，确认 Agent 提示词及相邻功能没有回归。
