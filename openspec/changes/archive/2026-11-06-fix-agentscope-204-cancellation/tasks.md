## 1. 依赖与运行控制

- [x] 1.1 在根及 ontology pom.xml 完整覆盖 #126 的三个版本变更，并核对差异。
- [x] 1.2 将普通聊天绑定到单次 AgentRun，中断回滚不再访问 AgentState.interruptControl；以取消回归和现有会话、并发测试验证。

## 2. 验证与交付

- [x] 2.1 增加订阅前取消、执行中取消后同轮重试及其它会话不受影响的回归测试，运行相关定向测试。
- [x] 2.2 运行 mvn test 和 openspec validate fix-agentscope-204-cancellation --strict，记录环境及结果，不执行 package。
- [x] 2.3 核对保持既有行为、无需主规格同步，准备替代 PR 说明与验证记录，并按 CLI 归档 change；远端 Windows 最新提交验证仍由 PR 门禁控制。
