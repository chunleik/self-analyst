## 1. 噪声标题事实排除（SPEC-WIKI-SRC-036）

- [x] 1.1 在 `self-analyst-wiki/src/test/java/com/selfanalyst/wiki/WikiTitleSamplerTest.java` 先补充失败测试，覆盖以下情况：锁屏、系统搜索、托盘、浏览器新标签页、翻译提示、无痕入口、SelfAnalyst 主窗口被排除；SelfAnalyst 安装器、卸载器、仓库页面和 VS Code `Untitled-1` 保留；噪声释放的预算被有主题事实使用。运行 `mvn -pl self-analyst-wiki -am '-Dtest=WikiTitleSamplerTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`，确认新增用例失败。
- [x] 1.2 在 `WikiTitleSampler` 中实现版本化的噪声规则表，在候选阶段过滤，并在采样 coverage 中输出 `noiseOmittedFacts`。验证：1.1 的命令全部通过。
- [x] 1.3 在 `WikiFactBuilderTest` 中补充指标守恒测试：同一周期排除噪声前后，活跃秒数、AFK 秒数、`appSecondsExact`、切换次数和 sourceCoverage 一致。将 `FACT_BUILDER_VERSION` 更新为 `wiki-facts-evidence-v6`。运行 `mvn -pl self-analyst-wiki -am '-Dtest=WikiFactBuilderTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`，确认通过。

## 2. 无活动周期本地完成（SPEC-WIKI-WKR-024）

- [x] 2.1 在 `WikiWorkerTest` 中先补充失败测试：只有 AFK 的小时和只有噪声的小时不调用 summarizer、不写准入账本，条目状态为 SUMMARIZED，`generation.mode=local_empty`，指标完整；完全没有事件的小时仍为 SKIPPED。
- [x] 2.2 在 `WikiWorker` 中增加无活动判定和本地结果构造，不获取生成租约。验证：2.1 的用例在 `mvn -pl self-analyst-wiki -am '-Dtest=WikiWorkerTest' '-Dsurefire.failIfNoSpecifiedTests=false' test` 中通过。
- [x] 2.3 在 `WikiFactBuilder.buildFactsFromChildren` 中跳过 `local_empty` 子条目的主题事实，但保留其指标聚合。在 `WikiFactBuilderTest` 中补充测试：周级聚合包含本地空的日级条目时，AFK 和活跃总量不变，且不产生“无活跃活动”主题事实。运行 1.3 的命令，确认通过。

## 3. 最终主题排序与限量（SPEC-WIKI-GEN-026）

- [x] 3.1 在 `WikiSummaryPipelineTest` 中先补充失败测试，覆盖以下情况：
  - 12 张卡片被折叠为 5 张加“其他零散活动”，引用真实有效，confidence 取最低值，折叠类型正确；
  - 沟通类主题有效秒数最高时排第一，`primaryTask` 等于该主题标题；
  - 卡片不超过 5 张时不生成折叠片段；
  - summary 超过 300 字符时由本地组合替代，且不追加调用；
  - 父层级按成员数排序，结果确定。
- [x] 3.2 在 `WikiSummaryPipeline` 最终装配前实现权重排序、折叠、`primaryTask` 派生和超长 summary 替代，并在 `generation` 中写入 `foldedTopicCards`；同时更新 `WikiSummaryPipeline.VERSION`。验证：运行 `mvn -pl self-analyst-wiki -am '-Dtest=WikiSummaryPipelineTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`，确认通过。

## 4. 证据边界声明清理（SPEC-WIKI-GEN-027）

- [x] 4.1 在 `WikiSummarizerTest` 中先补充失败测试，覆盖以下情况：
  - 尾句免责声明被删除，并计数为 1；
  - 片段中的“不表明发送消息或参会”子句被删除；
  - 片段只有声明时，用标题组合替代；
  - “排查连接不返回数据的问题”被保留；
  - “已完成发布”仍被拒绝；
  - 新提示词不含“不证明”否定清单。
- [x] 4.2 修改 `WikiTopicProtocol.RULES` 和 `WikiSummarizer.NARRATIVE_RULES`，改为正向措辞；在叙述校验之前实现子句清理，并写入 `disclaimerClausesRemoved`；同时更新 `WikiTopicProtocol.VERSION` 和 `WikiSummarizer.PROMPT_VERSION`。验证：运行 `mvn -pl self-analyst-wiki -am '-Dtest=WikiSummarizerTest,WikiSummaryPipelineTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`，确认通过。

## 5. 评测与整体验证

- [x] 5.1 在 `self-analyst-wiki/src/test/resources/wiki-quality/` 中新增一个匿名合成夹具，复现本次问题模式：大量噪声窗口、沟通应用时长占优但标题种类少、10 个以上的零散主题，并登记到 `index.json`。验证：`WikiQualityEvaluation` 离线模式能生成报告，报告中的 `noiseOmittedFacts > 0`、最终片段数不超过 6。
- [x] 5.2 可选步骤，需要用户提供模型凭据：用 live 模式对 5.1 的夹具分别运行改动前后的版本，对比片段数、primaryTask 和免责声明出现次数，把结论写入本 change 的评测记录；报告不提交真实活动数据。结论见 `evaluation.md`。
- [x] 5.3 运行 `mvn -pl self-analyst-wiki -am test`，确认 wiki 模块和依赖模块的测试全部通过；再运行 `mvn test`，确认跨模块测试（包括读取 Wiki 条目的桌面端测试）通过。
- [x] 5.4 运行 `openspec validate improve-wiki-summary-focus --strict`，确认通过。

## 6. 规格同步

- [x] 6.1 实施和验证完成后，使用 `openspec-sync-specs` 把四项新增 Requirement 同步到 `openspec/specs/llm-wiki/spec.md`。确认主规格中的 SPEC-WIKI-SRC-036、WKR-024、GEN-026、GEN-027 各出现一次，并重新运行 5.4 的命令。

## 7. 审查修复

- [x] 7.1 `WikiDisclaimer` 改为按分句清理：否定声明里的完成字样随声明删除，转折词后的成果断言保留并被拒绝，同句中声明前的主题描述保留。验证：`WikiSummarizerTest.negatedOutcomeWordsAreStillDisclaimersButContrastedOutcomesAreRejected` 通过。
- [x] 7.2 在 proposal、design 和 SPEC-WIKI-SRC-036 中写明看板当前窗按 SPEC-DSUM-LIVE-002 共享去噪。验证：`SummaryServiceTest.currentWindowSharesWikiNoiseExclusionWithoutChangingStatistics` 通过。
- [x] 7.3 SPEC-WIKI-WKR-024 和 SPEC-WIKI-GEN-026 的父层级场景改为周级。验证：`WikiFactBuilderTest.parentKeepsLocalEmptyMetricsWithoutUsingThemAsTopics` 使用日级子条目和周级周期并通过。
- [x] 7.4 本地完成条目不生成语义文档，也不阻塞语义文档发现。验证：`WikiEmbeddingWorkerTest.localEmptyEntriesAreNeitherIndexedNorLeftBlockingDiscovery` 通过。
- [x] 7.5 主题协议不再对被忽略的模型 `primaryTask` 做叙述校验。验证：`WikiSummaryPipelineTest.ignoredModelPrimaryTaskCannotFailTheResponse` 通过。
- [x] 7.6 离线评测辅助方法移入 `WikiQualityEvaluation`，噪声规则补充英文托盘和翻译提示标题。验证：`WikiQualityEvaluationTest` 和 `WikiTitleSamplerTest` 通过，生产代码中不再有 `offlineSegmentCount`。
- [x] 7.7 重新运行 `mvn test`、`openspec validate --specs` 和 `openspec validate improve-wiki-summary-focus --strict`，确认全部通过，并确认增量规格与主规格中的四项 Requirement 文本一致。
