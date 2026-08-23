# AgentState 长会话压缩规格

> 本规格定义 SelfAnalyst 在 AgentScope Java 2.0.1 上的长会话上下文治理。它补充
> [`chat-session-store.md`](chat-session-store.md)，不改变 UI transcript 的可见正文契约。

## 1. 背景与版本边界

`agentscope-core:2.0.1` 的裸 `ReActAgent` 会持久化完整 `AgentState.context`，但不会自动压缩。
官方压缩实现位于 `agentscope-harness:2.0.1`：`CompactionConfig`、
`ConversationCompactor`、`TokenCounterUtil` 和 `CompactionMiddleware`。

本阶段不把整个应用直接替换为 `HarnessAgent`，原因如下：

- Harness 默认的 workspace、memory、filesystem、skills、subagents 与项目已有能力重叠；
- 2.0.1 的 compactor 在摘要模型失败时会生成 `(Summarization failed: ...)`，官方 middleware
  随后仍会用该字符串覆盖旧前缀；
- compaction 直接调用模型，不经过现有 `PlanMiddleware`，需要额外 token 计量；
- Harness overflow retry 的配置与普通 compaction 不完全一致，不能作为本阶段的无风险替换。

因此采用增量接法：继续使用 `ReActAgent`，复用 Harness 的官方阈值、token 估算、tool-pair
切分和摘要构件，在应用侧增加事务保护与用量计量。完整 Harness 迁移留待 workspace/memory/
sandbox 统一时再进行。

官方参考：

- [AgentScope Java v2.0.1](https://github.com/agentscope-ai/agentscope-java/tree/v2.0.1)
- [上下文压缩](https://java.agentscope.io/v2/zh/docs/harness/compaction.html)
- [Harness 架构](https://java.agentscope.io/v2/zh/docs/harness/architecture.html)

## 2. 双层上下文

- `{memory.dir}/chat-sessions/<sessionId>.json` 仍是 UI transcript 权威，保留用户可见正文。
- `AgentState.context` 只保留近期原始消息。
- `AgentState.summary` 保存更早历史的滚动摘要。
- 模型推理前临时注入 `summary + recent context`；摘要消息不写回 `AgentState.context`。
- 当前桌面状态、任务、timeline 等本轮快照通过 `RuntimeContext` 临时注入，不再拼入并永久保存
  到 user `Msg`。

## 3. 配置

```toml
[agent.compaction]
enabled = true
triggerMessages = 30
triggerTokens = 60000
keepMessages = 10
keepTokens = 12000
```

- message 与 token 任一阈值达到即尝试压缩；`0` 表示关闭该单项阈值。
- `keepTokens > 0` 时采用 token tail；为 `0` 时采用 `keepMessages`。
- 仅启用 message 阈值时强制使用有上限的 message tail；仅启用 token 阈值时，
  `keepTokens=0` 会归一化为低于触发阈值的安全窗口，避免 AgentScope 得到零 cutoff
  而静默跳过压缩。`keepMessages` 最大 100，`keepTokens` 最大 64000。
- 配置在 Agent 构造时生效，修改后需要重启。
- test defaults 关闭压缩；专门测试显式打开低阈值。

## 4. 事务压缩流程

压缩只能在现有 application-wide chat/delete gate 内执行：

1. 旧 server transcript 如需迁移，先完成一次性 seed。
2. 先检查 `userMessageId` 幂等；已有 terminal assistant 时直接返回，不触发摘要模型。
3. 加载当前 `(desktop, sessionId)` AgentState；`shutdownInterrupted=true` 或存在未配对
   ToolUse/ToolResult 时跳过压缩。
4. 调用 Harness `ConversationCompactor`，关闭其 memory flush 与 raw offload，避免与现有
   `MemoryStore`/UI transcript 重复。
   官方 cutoff 若落在 assistant 消息上，应用层向前扩展到所属 USER，保证 recent tail 不拆散完整 user turn；ToolUse/ToolResult 仍沿用官方配对保护。
5. 只有摘要非空、不是官方 failure marker，且压缩期间原 state 未变化时才提交：更新
   `AgentState.summary`、用 recent tail 替换 `context`、保存同一 AgentState。
6. 摘要失败、保存失败或状态变化时保留原磁盘状态；cache 在退出前清除。
7. compaction 模型调用计入 `UsageMeter.Category.SUMMARY`，继续受每日预算约束。

压缩不得重建 AgentState，因此 permission、tool、tasks、plan mode、reply id 等其它字段保持不变。

## 5. 本轮桌面上下文

- 会话模式以 shard 中 server-owned user content 与 `contextSnapshot` 为准，忽略请求体伪造内容。
- `contextSnapshot` 在 `RuntimeContext` 中传递；`ConversationContextMiddleware` 在每次 reasoning
  前把它插入当前 user 前面，角色为 USER、标记为 reference-only data。
- 序列化后最多 12000 字符；前端同时把 task/timeline/evidence 投影到有限字段与长度。
- ActivityWatch `queryEvents.limit` 被限制为最多 500，所有 ActivityWatch 工具结果最多 80000
  字符，避免单次 tool result 在同一 ReAct call 内撑爆上下文。
- legacy drawer 仍走原兼容路径，其固定 legacy slot 不改变。

## 6. 测试与构建

- compactor 单测覆盖成功压缩、摘要失败原文件不变。
- middleware 单测覆盖 summary/current context 临时注入且不污染 AgentState。
- 本地 OpenAI-compatible HTTP 集成测试覆盖压缩、重启恢复、瞬时快照不落盘和幂等重试零调用。
- `UsageMeteredModel` 测试覆盖 compaction token 计量。
- `self-analyst-app` Maven `test` 阶段必须调用 Node 内置 test runner；Node 缺失时构建失败。
- 根项目 `mvn test` 是统一入口。不得无参数执行根 `mvn verify`，因为 integration verifier
  会访问真实麦克风、前台窗口和 OCR；安全打包使用：

```bash
mvn -pl self-analyst-app -am verify -DskipTests
```
