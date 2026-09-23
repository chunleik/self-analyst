## 1. 文案策略

- [x] 1.1 更新 WikiSummarizer 内部统计与输出提示词、版本及置信度约束，测试覆盖缺失AFK、旧子摘要、无模型metrics和单次调用。
- [x] 1.2 实现 WikiNarrativePolicy 及全字段调用，回归验证常见中英统计话术被拒绝、技术参数可用且错误不含原文。

## 2. 验证和规格

- [x] 2.1 运行 Wiki 模块测试，并用既有冻结三日快照调用 DeepSeek 复验所有文案字段，保存独立结果。
- [x] 2.2 同步主规格、双语README和架构说明，运行严格校验、检查差异并归档。

验证记录：修改前新增提示词/置信度回归2项失败；修复后 `mvn -pl self-analyst-wiki -am '-Dtest=Wiki*Test,OpenAiCompatibleEmbeddingClientTest' '-Dsurefire.failIfNoSpecifiedTests=false' test` 81项通过。追加持久化失败路径后，`WikiWorkerTest,WikiSummarizerTest,WikiNarrativePolicyTest` 最终22项通过，固定错误不保存响应原文。

同一冻结快照的三次 deepseek-flash 真实调用均通过生产解析和全字段文案校验；结构化活动指标与上一轮逐项一致，结果保存在忽略目录 `.tmp/summary-eval-narrative/comparison.md`，不进入代码仓库。主规格及双语README、docs/architecture.md 已同步；28份主规格严格校验通过。已有摘要、当前窗本地模板和独立统计详情没有改写，旧历史文档仍保留在 docs/archive/。
