## 1. 配置解析与生效策略

- [x] 1.1 在 self-analyst-app 的 config 包提取带来源的共享解析逻辑，接入 Config.load；以 ConfigTest 或新增 ConfigResolverTest 验证 TOML、环境变量、默认值、JVM 例外、aw.port、删除与显式空密钥。
- [x] 1.2 在 SupportedKeys 或同包集中声明生效策略，替换 DesktopConfigController 和 ConfigTools 的独立重启集合；测试五项 LLM 热更新键、maxIters/预算/压缩阈值仍需重启及未知 raw 键兼容。
- [x] 1.3 修正 UserConfigStore 消费方的有效值读取与结构化显式覆盖保存；测试环境模型与默认模型不同、显式保存默认值仍写入 TOML，以及读取结果包含正确来源。
- [x] 1.4 建立按组件保存的实际运行快照与差异比较；用单元测试验证混合保存、连续保存保留待重启提示、恢复原值清除提示及 Embedding 继承密钥差异。

## 2. 模型运行版本和资源所有权

- [x] 2.1 检查本地 AgentScope 的 ReAct、模型、toolkit、state store 关闭及缓存语义，确定版本独占资源和共享资源适配；在 agent 包新增组件测试，证明销毁候选不会关闭共享工具或会话存储，并将结论更新到 design.md。
- [x] 2.2 拆分 SelfAnalystAgent 的稳定服务与 LLM runtime 构造，支持可用和未配置两个运行状态；测试新版本仅替换五项参数，不重新注册工具，不应用 pending 语言、maxIters 或压缩阈值。
- [x] 2.3 实现运行版本发布、租约和退休清理；新增 LlmRuntimeManagerTest 覆盖活跃旧版本保留、无人引用释放、候选失败、重复切换、取消和异常恰好释放一次。
- [x] 2.4 将 SelfAnalystAgent 聊天入口及 TransactionalAgentStateCompactor 接入同一轮租约；用会话与压缩测试验证工具前后模型一致、历史保留、惰性请求在执行时取版本及现有 busy/取消语义不变。

## 3. 保存与运行时应用协调

- [x] 3.1 在 config 包实现统一配置应用服务及串行提交，协调严格校验、候选准备、文件替换、快照发布与关闭；新增 ConfigApplicationServiceTest 注入构建和写盘失败，验证文件、运行版本、资源清理和保存版本顺序。
- [x] 3.2 将 DesktopConfigController 的 raw/结构化保存及 ConfigTools 迁移到应用服务；用控制器与工具测试覆盖相同非法输入拒绝、注释逐字保留、空值删除兼容、未知键提示及各入口应用结果一致。
- [x] 3.3 将 DesktopFileController 等写入同一配置文件的应用入口纳入持久化协调；用文件设置现有测试及并发配置测试验证不丢失其它键，并保持专用文件采集热更新行为。
- [x] 3.4 增加“聊天工具调用中保存模型”的回归场景，验证保存不等待聊天 gate、当前轮旧版本完成、下一轮新版本生效；同时测试两个保存与关闭竞争无死锁且结果顺序可解释。

## 4. 首次配置恢复与后台调用

- [x] 4.1 调整 AppSession 和桌面消费方持有稳定 Agent 门面，使无密钥启动时本地存储及会话仍可用；新增 AppSession 相关集成测试验证首次补填、清空、再次补填及桌面状态变化无需重启。
- [x] 4.2 将桌面摘要、Wiki LLM 回调和长期记忆提取接入逻辑任务租约，恢复仅因模型未配置而等待的已启用服务；用假模型验证后台任务新版本生效、多调用任务版本一致、已禁用服务不被启用。
- [x] 4.3 保留同一 UsageMeter 及既有 AGENT/SUMMARY 计量包装，新增或扩展 UsageMeteredModelTest 与 Agent 计量测试，验证切换前后累加、取消仅计量一次及 block 状态不被重置。
- [x] 4.4 协调 AppSession.close、当前工作终止和版本资源退休；通过生命周期测试验证关闭后拒绝保存及新工作、旧版本最终释放、工具和存储只关闭一次。

## 5. 接口与桌面反馈

- [x] 5.1 在 DesktopServer 注册 GET /desktop/config/effective，并为保存响应添加版本及组件 application 信息；接口测试覆盖兼容字段、认证保护、来源、draining 结束、unavailable 和 Embedding 待重启提示。
- [x] 5.2 统一连接测试与待测 TOML 解析，保留旧参数请求入口；使用回环 HTTP 假服务测试未保存显式值、空值与省略值、掩码兼容、当前保存参数兜底，并断言不写盘、不切换运行版本。
- [x] 5.3 更新 desktop-ui/config.js 及相关 API 与中英文文案，展示来源和应用结果，重新打开时加载状态，有旧版本工作时有限刷新；新增 Node 测试覆盖保存成功、失败保留脏态、未配置、待重启和停止轮询。
- [x] 5.4 使用假密钥检查配置查询、错误响应和日志脱敏，维持 raw 原文编辑边界；接口测试断言除已认证 raw 外无明文密钥或密钥摘要，并用桌面页面截图验证新增状态信息可读。

## 6. 集成验证

- [x] 6.1 运行针对性测试：mvn -pl self-analyst-app -am '-Dtest=ConfigTest,ConfigResolverTest,ConfigApplicationServiceTest,LlmRuntimeManagerTest,DesktopConfigControllerTest,ConfigToolsTest,UsageMeteredModelTest,TransactionalAgentStateCompactorTest' '-Dsurefire.failIfNoSpecifiedTests=false' test；按实施确定的测试类名称更新命令，确认每个计划场景实际执行而非被跳过。
- [x] 6.2 用本地假模型／回环服务完成保存前后流式聊天、摘要并发、工具内改配置、首次恢复、401 不回退、构建/写盘失败及连续保存的端到端测试；记录通过的测试名和模型调用证据，不使用真实密钥或真实供应商。
- [x] 6.3 完成应用相关全套回归：mvn -pl self-analyst-app -am test；若实际实施涉及其它模块行为则运行 mvn test，确认既有会话删除、压缩、预算与文件设置用例及 Node UI 测试通过。

## 7. 规格同步和交付

- [x] 7.1 实施验证后使用 openspec-sync-specs 同步本 change 的 user-configuration、agent-runtime、llm-budget、agent-context-compaction 增量；运行 openspec validate --specs --strict，确认既有 Requirement ID 和未修改场景保留。
- [x] 7.2 更新 README.md 和 docs/architecture.md 中的配置生效范围、首次配置体验及运行版本生命周期，明确外部编辑、Embedding 和其它启动期配置边界；对照增量规格人工核对文档与实现一致。
- [x] 7.3 整理本 change 的验证记录、UI 截图位置与实际限制，运行 openspec validate enable-llm-config-hot-reload --strict 及 git diff --check；确认任务均有完成证据后按 openspec-archive-change 流程收尾。
