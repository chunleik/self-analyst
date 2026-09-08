# LLM 配置热更新验证记录

## 结果

2026-09-08 完成 enable-llm-config-hot-reload 的实施。五项 LLM 参数通过应用保存后用于新工作，
旧轮次继续使用既定版本；首次配置、清空与再次恢复均保留会话和用量。

完整 Maven reactor 构建成功：Java 共 616 项，失败 0，错误 0，
条件跳过 5 项；Node 桌面 UI 测试 94 项全部通过。新增热更新与相关针对性测试均实际执行，
没有跳过。既有跳过项为两个 UIA 可选 smoke test、一个容量 benchmark，以及两个符号链接环境用例。

## 验证命令

本机使用 JDK 21 和已配置的阿里云 Maven settings。先按任务清单运行针对性测试，再执行完整测试。

```powershell
$env:JAVA_HOME = 'C:\\Program Files\\Java\\jdk-21'
mvn -o -s C:\\Users\\10478\\.m2\\settings-aliyun.xml test
openspec validate --specs --strict
openspec validate enable-llm-config-hot-reload --strict
git diff --check
```

OpenSpec 的四份增量规格逐条与主规格核对一致；21 项主规格严格校验全部通过。
构建日志位于工作区忽略的输出文件中，不作为用户数据或长期文档提交。

## 行为证据

| 范围 | 测试及完成证据 |
|------|----------------|
| 解析、来源、默认覆盖 | ConfigTest、ConfigResolverTest；覆盖 TOML/环境优先级、显式空值、继承密钥与规范化运行值 |
| 提交、失败与并发 | ConfigApplicationServiceTest；覆盖候选构建失败、文件写入失败、连续修改、关闭竞争、待重启差异和恢复原值 |
| 租约与资源 | LlmRuntimeManagerTest；覆盖旧版本存活、候选释放、幂等关闭、最后使用者退出后退休 |
| 整轮与任务版本 | LlmHotReloadIntegrationTest；回环服务捕获实际 model、Authorization、max_tokens、temperature 和 usage，验证旧聊天/新摘要并行、惰性执行、多次摘要调用固定版本 |
| 工具内保存 | configurationToolKeepsWholeTurnOnOldModelWithoutDeadlock；实际模型 tool_calls 调用 ConfigTools，当前轮两次推理仍用旧模型，下一轮切换 |
| 压缩和预算 | compactionAndFollowingReasoningKeepTheSameVersion、changingModelCannotResetAnExceededBudget，以及既有压缩/用量测试 |
| 首次配置及后台恢复 | AppSessionLlmRecoveryTest、firstSetupClearAndRestoreKeepSameMemoryAndUsage、WikiWorkerTest.unconfiguredModelStaysPendingAndRecoversNextAttempt |
| 取消与存储保留 | cancelledOldWorkReleasesVersionAndSharedStateSurvives、failedCandidateDisposalKeepsToolkitAndStateStoreOpen；取消后可继续聊天和删除会话 |
| API 与认证 | DesktopConfigControllerTest、DesktopServerIntegrationTest、EventServerSecurityTest；覆盖草稿测试不保存、显式空密钥不回退、兼容字段、来源元数据、原文编辑与认证边界 |
| 桌面反馈 | config-runtime.test.mjs；覆盖生效状态、来源、错误保留脏态、有限轮询、关闭取消以及轮询不重建用户展开的表格 |

新增运行时错误只输出受限原因或 HTTP 状态，不包含底层认证请求。完整测试日志检查没有出现
用于泄露检测的完整假密钥或 Bearer 认证值；非 raw 配置响应验证了脱敏，已认证 raw 仍保留原文。

## 视觉验证

截图位于 evidence/config-runtime.png。使用隔离的临时数据和无实际用途的示例密钥，通过真实
DesktopConfigController 与生产配置页脚本检查配置组件；没有连接真实模型供应商，也没有修改个人运行配置。
截图展示旧版本工作尚未结束、新模型配置来源及其它组件的重启提示。展开来源表后保持稳定，
密钥只在原文编辑区保留测试文本，来源表显示掩码。本项为浏览器内配置组件验证，未重新打包 Tauri 安装包。

## 实施细节与边界

- AgentScope 2.0.1 的 ReActAgent.close 只解绑关闭回调并清理缓存。模型使用共享 HTTP transport，
  版本退休不关闭共享工具、transport 或会话存储。
- 新 LLM 工作采用新参数，不重算已存摘要；未配置模型时 Wiki 保持 PENDING，下一次调度重试。
- 本期不监听外部配置文件变动，不热更新 Embedding 客户端、预算策略、压缩阈值、maxIters、
  联网搜索、端口或存储目录。相关差异由配置页单独提示。
- 文件设置专用接口维持已有采集热更新，并参加统一持久化协调。
- README.md 与 docs/architecture.md 已更新；docs/archive/ 的历史资料继续保留，不作为第二套现行规格。
