## Why

PR #126 升级 AgentScope 后删除了 AgentState 上的中断控制接口，导致 Windows 编译失败。需要适配每次运行独立的取消控制，同时保持现行聊天取消、状态回滚与重试契约。

## What Changes

- 覆盖 PR #126 的 AgentScope、Jackson 与 Neo4j 测试依赖补丁升级。
- 使用单次运行句柄登记取消回调，覆盖订阅前及执行中的取消，移除已经不存在的会话中断重置接口。
- 增加取消后同轮重试、会话隔离及不残留中断状态的回归测试。
- 仅兼容依赖新 API，不改变用户行为；现行 agent-runtime 和 desktop-chat 规格保持不变。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

无。本变更保持 SPEC-AGT-003 会话取消隔离和既有状态回滚语义，设置 skip_specs: true。

## Impact

涉及根 pom.xml、self-analyst-ontology/pom.xml、SelfAnalystAgent 及相关 Java 测试。不调整产品版本、不进行本地打包；远端 Windows 全量验证仍是合并门禁。
