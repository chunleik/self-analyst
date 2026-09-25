## Context

动机见 proposal.md。与本设计相关的现状如下：

- 小时级事实由 `WikiFactBuilder.buildCompleteFacts` 构造，`WikiTitleSampler` 生成带有效秒数 `s` 的结构化标题事实；分层生成走 `WikiSummaryPipeline` 和 `WikiTopicProtocol`，最终主题卡片最多 24 张（`MAX_CARDS`），并由 `decorate` 装配元数据。
- 小时、半天和日直接读取原始事件；只有周、双周和月由 `buildFactsFromChildren` 从子条目汇总。汇总时子条目的任务片段转为 `source=wiki` 的事实，这些事实的 `activeSeconds` 为 `null`，所以这些层级无法按时长加权。
- 看板当前窗（`SummaryService`）与 Wiki 共用 `WikiTitleSampler`，这是 SPEC-DSUM-LIVE-002 要求的“同语义标题事实”。
- `WikiTopicProtocol.RULES` 和 `WikiSummarizer.NARRATIVE_RULES` 都含“标题不证明运行/配置/交付项目、完成成果、发送消息或参加会议”这类否定清单，抽样结果显示模型会把它原样写进文案。
- `WikiWorker.isEmpty` 只在活跃秒数、AFK 秒数和标题候选都为零时标记 SKIPPED；只有 AFK 的小时会发起一次模型调用。
- `buildFactsFromChildren` 会汇总全部子条目的 metrics。如果把只有 AFK 的小时改为 SKIPPED（没有 metrics），父层级的 AFK 总量就会变少，违反 SPEC-WIKI-GEN-020 的“完整本地指标不变”。

## Goals / Non-Goals

**Goals:**

- 送入模型的事实只保留有主题的标题，并把释放出的预算留给这些事实。
- 最终片段数量有上限，主次由本地可复现的权重决定。
- 文案不再出现证据边界免责声明，同时保留现有的成果断言拒绝规则。
- 无活动周期不产生付费调用，同时保持父层级指标守恒。

**Non-Goals:**

- 不修改桌面实时摘要 `SummaryPromptService` 及其 `summary.prompt` 文案。看板当前窗的标题事实会随共享采样器一起去噪，这是有意保留的行为，不另设开关。
- 不增加用户可配置的噪声清单或隐私排除清单（留给隐私相关的 change）。
- 不调整置信度上限规则，不新增交互信号，不给片段增加时间范围。
- 不重算已有摘要。

## Decisions

### 1. 在采样前排除噪声，而不是交给模型忽略

在 `WikiTitleSampler` 生成候选之后、按预算选择之前过滤噪声候选。只过滤标题事实，`ActivityStatistics` 的输入不变，所以指标保持一致。判定规则集中在一个版本化的只读规则表中，包括：

- 按应用排除：`LockApp.exe`、`SearchHost.exe`、`ShellHost.exe`、`ShellExperienceHost.exe`、`StartMenuExperienceHost.exe`、`SelfAnalyst.exe`。
- 按应用加标题排除：`explorer.exe` 的 `Program Manager`、“系统托盘溢出窗口”和 `System tray overflow window.`。浏览器进程（`chrome.exe`、`msedge.exe`、`firefox.exe`）的“新标签页 / New Tab”“无标题 / Untitled”“新的无痕式标签页 / New Incognito Tab / InPrivate”“翻译此页？ / Translate this page?”等，使用精确匹配或带“ - 浏览器名”后缀的精确匹配。

备选方案是只在提示词里要求模型忽略。不采用的原因是：噪声仍会占用预算和 token，模型也不一定遵守。另一个备选方案是用正则做模糊匹配。不采用的原因是误伤风险高，而且难以测试。规则表变化时，更新 `FACT_BUILDER_VERSION`。

另一个备选方案是只在 Wiki 路径启用过滤、看板当前窗保持原样。不采用的原因是它违反 SPEC-DSUM-LIVE-002，会让同一时段在两处得到不同的标题事实。

### 2. 排序和限量在最终装配阶段由本地执行

在 `WikiSummaryPipeline` 得到最终卡片（DIRECT 或 FINAL 阶段）之后、`decorate` 之前执行：

1. 计算每张卡片的权重。小时、半天和日取 `memberInputIds` 对应事实的 `activeSeconds` 之和（`null` 计为 0）。周、双周和月（全部成员事实都是 `source=wiki`）取成员数量。再以最早成员事实 ID 和卡片 ID 作为确定性的平局规则。
2. 保留前 5 张卡片。其余卡片合并为本地卡片“其他零散活动”，规则如下：
   - 代表引用按被合并卡片的权重顺序各取第一个，最多 3 个；
   - `memberInputIds` 取被合并卡片的并集；
   - `claimType` 为 inferred 或 observed，confidence 取被合并卡片中的最小值；
   - summary 在本地组合为“涉及：标题 A、标题 B……”，并截断到 500 字符以内的完整项。
3. `primaryTask` 取第一张卡片的标题。主题协议解析时仍要求模型返回 `primaryTask` 字段（SPEC-WIKI-GEN-010），但不再对它做叙述校验：这个值不会展示，因它不合规而整条失败只会浪费一次付费重试。
4. summary 超过 300 字符，或清理后为空时，改为本地组合的“主要涉及 A、B 和 C。”（最多取 3 个主题标题）。

备选方案是在提示词里限制卡片数量，超出时拒绝响应。不采用的原因是：拒绝会进入付费重试，而且模型对“重要性”的判断正是这次要修正的偏差。另一个备选方案是按标题数量加权，它会复现沟通类活动被低估的问题。

父层级按成员数量加权是一个折中：子任务事实本身不带时长。给子片段持久化权重需要改变数据模型，这次不做。

### 3. 证据边界声明在本地清理，不拒绝响应

提示词中删除否定清单，改成正向要求：“用查看、涉及、编辑中等动词直接描述主题；文案中不要写关于证据边界的说明。”`NARRATIVE_RULES` 和 `RULES` 同步修改。

保存前，先按句号、分号和换行把 summary 与片段 summary 拆成句子，再在逗号之后、转折词（但是、但、却、然而、不过）之前把句子拆成分句。同时满足以下两个条件的分句会被删除：

- 包含声明触发词：“不证明”“不表示”“不表明”“不代表”“仅表明”“仅反映”“仅为（窗口）标题”“仅描述查看”“标题本身不”；
- 声明对象属于行为词表：运行、配置、交付、发送、消息、参会、会议、完成、成果、提交、发布、通话、登录、审批、观察、查看、打开。

两个条件都要满足，是为了保留“排查连接不返回数据”这类技术描述。按分句而不是按整句删除有两个效果：

- 同一句里声明前的主题描述会保留，例如“涉及数据库同步，不证明已交付”保存为“涉及数据库同步。”；
- 否定声明里的“已完成”跟着声明一起删除，而转折词后面的“但已解决问题”自成一个分句，会被保留下来，再由现有的 `validateNarrative` 拒绝。

元数据中的 `disclaimerClausesRemoved` 按受影响的句子计数。

备选方案是把声明判为质量失败。不采用的原因是它会触发付费重试，而且模型很可能重复同样的尾巴。

### 4. 无活动周期以本地条目完成，而不是标记 SKIPPED

`WikiWorker` 在调用 summarizer 之前增加判定：排除噪声后没有候选标题事实，并且也没有子摘要。采样预算未选中但候选仍在时不走这条路径，避免把“有事实但没放进预算”误判成无活动。满足时，直接构造 `SummaryResult`，内容如下：

- summary 为“该时段没有可归纳的活跃活动。”，primaryTask 为“无活跃活动”；
- 片段为空，metrics 使用完整本地指标；
- `generation.mode=local_empty`，`calls=0`，`model` 字段记为 `local`。

这个过程不获取生成租约，也不写入准入账本。父层级的 `addChildFacts` 需要跳过 `generation.mode=local_empty` 的子条目，避免把固定描述当作主题事实。

本地条目也不进入语义检索。`WikiEmbeddingWorker.enqueueEntry` 会跳过 `model=local` 的条目；同时 `WikiStore.findSummarizedWithoutSemanticDocs` 在查询里排除这类条目。只做前一处是不够的：这类条目永远不会有语义文档，会一直占据每轮 20 条的发现额度，挤掉后面的正常条目。

备选方案是标记 SKIPPED。不采用的原因见 Context：它会让父层级丢失 AFK 和活跃时长。

### 5. 版本与兼容

以下版本号需要更新：

- `WikiTopicProtocol.VERSION` 改为 `wiki-topic-cards-v2`；
- `WikiSummaryPipeline.VERSION` 改为 `wiki-tree-v5-focus`；
- `WikiSummarizer.PROMPT_VERSION` 改为 `wiki-v9-focus`；
- `FACT_BUILDER_VERSION` 改为 `wiki-facts-evidence-v6`。

旧条目和旧检查点按现有规则读取和隔离，不做迁移。

新增的生成元数据字段为 `noiseOmittedFacts`、`foldedTopicCards`、`disclaimerClausesRemoved` 和 `localEmpty`。

## Risks / Trade-offs

- 噪声规则误伤有意义的标题（例如用户确实在研究锁屏设置）会导致证据丢失。缓解措施：只做精确的应用或标题匹配，由单元测试覆盖正反例，指标不受影响。
- 声明清理误删真实内容。缓解措施：要求同时命中声明触发词和行为词表，并按分句删除；测试覆盖技术否定句、否定范围内的完成字样和转折后的成果断言。
- 转折词切分可能误伤含“但”“却”字的普通词语（例如“不过滤”）。这只会让分句切得更细，不会删掉不含声明触发词的内容。
- 父层级按成员数加权，可能让“出现很多次但每次很短”的主题排在前面。当前接受这一点，后续可以在子片段持久化权重后改进。
- 本地组合的 summary 可读性不如模型文案。它只在超长或清理后为空时启用，并记录在元数据中，便于统计触发频率。
- 5 个主题的上限可能不适合周级和月级。目前统一使用 5，上限是常量，后续可以按层级调整，不影响规格结构。

## Migration Plan

发布后，新结束的周期会按新版本生成；已完成的条目不变。回滚时恢复代码和版本号即可：新版本写入的条目结构兼容旧读取路径，新版本检查点在旧版本下会因版本隔离而不被复用。

## Open Questions

- 5 个主题和 300 字符这两个阈值的实际效果，需要通过实施后的评测对比确认。如果需要调整，只修改常量和规格中的数值，不影响任务拆分。
