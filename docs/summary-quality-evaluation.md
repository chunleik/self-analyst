# 摘要质量评测

本轮已执行的测试、真实模型诊断及其局限见 [摘要优化验证记录](summary-quality-results.md)。

这套评测使用仓库中八组固定、脱敏的标题事实，检查采样覆盖、证据引用和生成成本。默认不调用模型、不读取私人活动库或用户配置。主题词命中是词面代理指标；JSON 合法、引用存在以及校验通过都不等于模型断言真实。

## 固定案例与离线验证

案例位于 `self-analyst-wiki/src/test/resources/wiki-quality/`，`index.json` 固定案例顺序：

| 案例 | 检查重点 |
| --- | --- |
| `chinese-topic-diversity` | 中文近重复不能挤掉独立主题 |
| `four-layer-budget` | 存在可行预算组合时覆盖四个时间层 |
| `long-title-tail` | 长标题保留尾部区别信息及有效 Unicode |
| `short-meeting` | 较短会议标题仍能代表独立时间层 |
| `cross-application-project` | 同一项目跨应用的不同证据保留 |
| `generic-window-context` | 通用窗口的额外上下文、未知标题及正文隔离 |
| `missing-activity-coverage` | 观察与有效活动区分，缺失覆盖不伪装完整 |
| `long-activity-history` | 大量标题、四时段及有界输入 |

使用 JDK 21，在仓库根目录运行：

```powershell
mvn -pl self-analyst-wiki -am '-Dtest=WikiTitleSamplerTest,WikiSamplingQualityTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

`WikiSamplingQualityTest` 写出 `self-analyst-wiki/target/wiki-sampling-quality.json`。报告没有时间戳或模型随机输出；同一代码与夹具的检查结果可重复。任何固定期望失败都会使测试失败，同时保留报告。指标包括主题覆盖、时间层覆盖、窗口有效活动覆盖、来源引用、观察语义、预算和打乱输入后的确定性。

观察区间仍完整保留，但纯观察只用起点参与时间层调度，不用长观察跨度争取多个时间层。窗口与上下文在各自预算内分别保留可行覆盖；主题匹配和时间覆盖均不改变本地全量指标。

## 默认离线的比较入口

```powershell
./scripts/evaluate-wiki-quality.ps1 -Strategy Both -Repeat 3
./scripts/evaluate-wiki-quality.ps1 -Strategy Both -Repeat 3 -Budgets 1000,2400
```

不指定 `-Budgets` 时，每个案例使用夹具中的预算。默认报告位于 `self-analyst-wiki/target/wiki-quality-evaluation.json`。可用 `-Output` 写到另一个位置，用 `-Case chinese-topic-diversity` 只运行一例。

脚本编译测试源码，并通过 Maven `dependency:build-classpath` 生成运行类路径，不修改 POM。首次构建可能下载 Maven 依赖；已有缓存时可加 `-MavenOffline`，已完成构建时可加 `-SkipBuild`。可用 `-JavaHome` 指定 JDK 21，`-MavenSettings` 选择本机 Maven settings。离线评测入口本身不会请求模型服务。

两种策略只改变进入生成链路的事实选择：

- `Baseline`：对完整归组事实按时间顺序取前缀，遇到首个放不下的事实便停止。
- `Current`：使用当前中文新颖度、独立来源预算和时间覆盖采样。
- `Both`：对同一案例、模型、预算和重复次数执行两种策略。

两者使用相同的生产提示词、结构化输出格式、事实 ID、引用校验、置信度规则和 `WikiSummaryPipeline`。这里的基线是“同契约下的顺序采样对照”，**不是历史旧版的精确重放**。该比较隔离采样差异，不用于宣称完整事实树形汇总的端到端收益；完整分块、请求大小、缓存和预算边界另由生产链路测试验证。

另可用 `-FullHistory`（Java 参数 `--full-history`）单独检查完整事实的树形路径。它只允许 `Current`，把全部归组事实直接交给 pipeline，并以 `-Budgets` 约束每块事实字符数；显式预算至少为 1000，缺省使用不低于 1000 的案例预算。报告的 `inputMode=full-history-tree` 和 `strategy=current-full-history-tree` 会明确区分这一功能试验与默认采样对照。例如已配置模型环境后，进行单例、单次、至多六次调用的真实树形试验：

```powershell
./scripts/evaluate-wiki-quality.ps1 -Live -Strategy Current -FullHistory -Case long-activity-history -Repeat 1 -Budgets 2400 -MaxCalls 6 -MaxTotalCalls 6
```

省略 `-Live` 仍为离线输入检查，不会执行任何模型或树形生成调用。

## 显式真实模型评测

仅在传入 `-Live` 时才发送 HTTP 请求。运行环境必须预先注入以下变量，脚本不读取现有用户配置，也不显示变量值：

| 环境变量 | 含义 |
| --- | --- |
| `WIKI_EVAL_API_KEY` | OpenAI-compatible 服务的 API key |
| `WIKI_EVAL_BASE_URL` | 服务 API 根地址，例如包含 `/v1` 的地址；也接受完整 `/chat/completions` 地址 |
| `WIKI_EVAL_MODEL` | 固定模型标识 |

```powershell
./scripts/evaluate-wiki-quality.ps1 -Live -Strategy Both -Repeat 3 -Budgets 1000,2400
```

凭据应通过当前进程环境或凭据管理器注入，不写进脚本、夹具或版本控制。输入只来自上述固定案例。`-Live` 默认使用 `-Transport Formal`：app 测试模块中的 `WikiFormalEvaluationSession` 直接包装正式 `SelfAnalystAgent.PlainTask`，复用同一模型构建、中文 system prompt、固定 `temperature=0.2`、完整输入 token 估计、preflight、详细 usage 和超时处理。不发送 `max_tokens`、`max_completion_tokens`、`max_output_tokens` 或额外 `response_format`，也不按应用拆分提示词。

正式 plain 固定 `sdkMaxAttempts=1`，关闭 SDK 对429、5xx等错误的内部重试，同时保留既有超时与其他执行
默认值。每次外层调用只对应一次 SDK HTTP 尝试和一次计量，下一次请求须重新经过周期准入；报告在顶层及
正式 `requestConfiguration` 中记录该设置。旧 SDK 默认三次尝试的诊断不应被当作“一次预留一次 HTTP”的证据。

每轮在报告目录旁新建独立的 `wiki-eval-isolated-*` 目录，仅在其中创建评测用 memory、状态与每日用量；不读写应用的用户配置、用量或数据库，凭据只存在于内存。脚本为正式模式使用专门的关闭日志配置，不创建用户应用日志。报告仅保存模型名、稳定模型/system prompt 指纹、参数和安全计量，不包含 API key、endpoint 或临时数据路径。正式模式首次构建会包含 app 模块；`-SkipBuild` 应提供 app 侧已生成的依赖 classpath，而不是旧 wiki-only classpath。

早期临时三日脚本曾误用聊天温度 `0.7`，且部分旧 HTTP 评测发送了输出上限与 `response_format`。这些结果只能作为当时参数下的诊断，不能与正式 plain `0.2` 结果混称同配置基线。新报告保留这一差异提示。

只有显式选择 `-Transport DiagnosticHttp` 才使用旧 HTTP 诊断请求形状（user-only、`response_format=json_object`、默认输出上限 4096）；此时可用 `-MaxOutputTokens` 调整上限。报告标记 `formalConfiguration=false`，不得将其作为产品默认效果。正式模式拒绝非零 `-MaxOutputTokens`：

```powershell
./scripts/evaluate-wiki-quality.ps1 -Live -Transport Formal -Case cross-application-project -Repeat 1 -MaxTotalCalls 6
./scripts/evaluate-wiki-quality.ps1 -Live -Transport DiagnosticHttp -Case cross-application-project -Repeat 1 -MaxOutputTokens 4096
```

默认每个案例/预算/策略执行 3 次；每次创建新的 pipeline，避免中间缓存影响重复对比。默认完整请求预算 32000 字符、每轮最多 6 次调用、整次评测最多 300 次模型调用尝试、每轮总超时 60 秒。对应参数为 `-RequestChars`、`-MaxCalls`、`-MaxTotalCalls` 和 `-TimeoutSeconds`。正式模式按生产管线检查 prompt 与 system overhead；HTTP 诊断模式另检查序列化 JSON 长度。周期账本仍采用生产默认的累计调用/token 准入规则，正式模式的每日 gate 使用隔离用量，默认 block / 100000000 tokens。4096 输出预留只用于准入估计，不是服务端输出上限；真实输出可能超过预留，报告不承诺账单硬上限。不会自动扩大预算或补发无限修复请求。

服务错误、超时、无效 JSON、引用/文案拒绝及额度不足都保留为独立记录，不丢弃失败以抬高成功率。正式模式保留详细 usage、安全错误码与可获得的 HTTP 失败状态；正式 `PlainTask` 当前不暴露成功 HTTP 状态或 `finishReason`，这些字段保持 null，不根据输出形状猜测。HTTP 诊断模式记录受限的 `finishReason`，返回 `length` 时标记 `EVAL_OUTPUT_TRUNCATED`。错误不保存响应原文、API key 或 endpoint。取消后先等待 receipt 与周期结算完成，再关闭隔离 runtime，避免报告过早结束或丢失已知用量。

报告还保存 `unvalidatedCandidate` 供诊断合成案例的误拒。它明确标注未经校验、需人工检查，仅从成功 HTTP 响应的模型内容中抽取 summary、primaryTask 和至多 24 个任务的 title/summary、受限应用/引用 ID、claimType/confidence；叙述字段每项至多 1200 个码点。不保存响应 metadata、headers、任意额外字段或原始错误正文。无效/截断 JSON 无法提取候选时只记原因，不回退保存原文。这些候选不得作为已通过校验的摘要使用，报告中的凭据值会在 JSON 编码前统一脱敏，含引号或反斜线的凭据也不例外。

`validationField` 仅保留生产校验器允许的稳定字段路径，例如 `taskSegments.evidenceFactIds` 和 `topicCards.memberInputIds`，不保留任意错误后缀。`candidateCounts` 单独记录原始 taskSegments/topicCards 总长度，以及前 24 项的应用、证据、成员、代表引用及来源主题数组原长度（非数组为 null）；各数组内容最多展示 16 项。这样可以诊断超长数组，避免把展示样本误当作模型原始输出。成员归属、主题卡片覆盖与少量展示证据分开解读，不以引用数宣称语义完整。

## 如何阅读报告

`runs` 中每条记录对应一个案例、策略、预算和重复编号：

| 字段 | 解释 |
| --- | --- |
| `inputSampling` | 候选数、选中数、字符开销和时间层信息 |
| `transport`、`requestConfiguration` | 正式或诊断模式、实际参数、稳定模型/system 指纹、计量约定；诊断请求不能冒充正式配置 |
| `inputTopicKeywordsProxy` | 输入标题中的预期主题词命中 |
| `status`、`errorCode` | 离线、成功、拒绝或失败，以及脱敏稳定错误码 |
| `validationField`、`candidateCounts` | 白名单校验字段路径和原始数组长度，帮助定位结构拒绝，不扩大原文保存范围 |
| `referenceValidation` | 是否通过当前生产引用校验；拒绝/失败时不宣称引用成立 |
| `topicKeywordHitsProxy` | 仅统计摘要与任务叙述中的主题词；不把自动生成的 evidence 文案算作命中 |
| `requests` | 每次请求的字符数、可获得的安全 provider 诊断、耗时和服务返回的输入/输出 token 用量；不可获得字段为 null |
| `calls`、`elapsedMillis` | 本轮模型调用尝试次数和总耗时；明确发出前阻断不计入 calls |
| `inputTokens`、`outputTokens`、`usageComplete` | 只有全部请求均返回对应 token 计数时才提供总量；缺失为 null，不当作零成本 |
| `manualTruthfulnessReview` | 人工标注无依据断言、遗漏重要主题和审查意见的空栏 |
| `unvalidatedCandidate` | 有界白名单候选，仅供人工诊断；失败/拒绝时同样保留，绝不表示生成成功 |
| `buildFingerprint`、`topicProtocolVersion` | 指纹包含采样、校验、管线、主题规划及主题协议代码，防止不同生成版本被混为同一次对比 |
| `generation` | 分别记录输入处理/省略、成员归类/未归类、卡片传递/省略和最终展示引用，以及周期 calls/tokens；不能混成一个“语义覆盖率” |

默认离线状态为 `offline-no-model`，调用数为 0；这不表示模型质量已经验证。真实调用中，`accepted` 仅表示通过当前结构和本地规则校验。建议至少比较 3 次重复，联合查看主题词代理命中、失败率、调用/token 成本与人工检查结果，不凭一次输出或某个单项分数决定效果。

默认 CLI 每个重复使用新 pipeline，目的是避免缓存影响模型对照；它自身不证明跨进程恢复。生产恢复由独立
`wiki-generation.db` 的周期账本和检查点支持：12 次/256000 tokens 是每个逻辑周期的终身累计默认额度，
不是每天重新发放；4096 输出 tokens 只是准入预留。成功块和最终结果可复用，已发布满 7 天的检查点可在
后续清理时回收，账本及未完成检查点不会因此清零。恢复评测应独立核对重启前后的调用/token 增量、
检查点命中和已发出但用量未知的预留，不能用多次无缓存重复代替恢复验证。输入或模型变化会隔离检查点，
但不重置周期账本；调高额度并重启后才可能恢复预算暂停。

人工检查至少回答：标题是否真的支持所写动作，是否把会议窗口误写成已参会，是否把开发标题误写成已完成/已发布，是否遗漏短但重要的主题，是否把跨日期或分离区间误写成连续活动。不要以引用 ID 存在代替这些判断。
