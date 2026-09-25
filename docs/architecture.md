# SelfAnalyst 架构

## 1. 总览

SelfAnalyst 使用 Java 21 Maven 多模块后端、Tauri 桌面壳和 Rust accessibility sidecar。系统以
“事实采集—本地存储—派生分析—Agent 查询”为主链路；内容事实限定为标题，不保存屏幕正文。

```text
Win32 前台窗口 ───────────────┐
Rust UIAutomation（临时树） ──┼─> TitleCapture ─> 内容事件 v2 ─┐
窗口/AFK watcher ────────────────────────────────> window/AFK ─┼─> 事件服务 ─> Wiki 聚合

用户配置的监控目录 ─> 文件系统元数据采集 ─> file-watch.db ─> FileTools / 桌面 API
                                   └─> metadata-only heartbeat ─> 事件服务通用历史

通过策略的 heartbeat/events/import ─> 事务性合并存储 ─> events.db（权威活动区间）
                                      └─> 24 小时重试回执（到期回收）
```

OCR、屏幕截图和声音/语音链路当前不存在。恢复背景见
[archive/removed-features/removed-ocr-audio.md](archive/removed-features/removed-ocr-audio.md)。

## 国际化资源与语言生效

`self-analyst-app/src/main/resources/i18n/languages.json` 注册正式语言及日期 Locale。后端启动时解析有效语言并固定在 Config 中，经桌面状态通道返回语言元数据；普通模型配置热更新不应用待重启语言。原生壳嵌入同一注册表和原生消息资源，使用本次受管端口及 token 读取后端语言后建立正常桌面入口。

页面消息位于 `desktop-ui/locales/<语言>.json`，后端固定消息位于 `i18n/messages/<语言>.json`，原生消息位于 `i18n/native/<语言>.json`；Agent Markdown 提示词继续位于 `prompts/agent/`。查找依次使用当前语言、英文、key，参数只替换一次。桌面错误可携带 `errorCode` 和 `errorParams`，保留既有错误字段及 HTTP 状态；未知错误保留可公开诊断信息，由本地化通用文案包裹。

扩展语言时增加注册项及上述各域资源，提供对应 Markdown 提示词，并运行 Java、Node 和 Rust 测试。各语言的目录 key、参数集合和提示词占位符需通过完整性校验；测试用第三语言仅放在测试资源中，不加入正式清单。无需修改业务渲染点或引入前端打包器。日期 Locale 仅影响展示，不改变时区、存储时间戳或聚合时间窗。

## 2. 模块边界

| 模块 | 边界 |
|------|------|
| `self-analyst-events` | 嵌入式事件服务、SQLite 事件存储、内容事件策略和迁移 |
| `self-analyst-content` | 前台窗口查询、UIA 临时读取、标题候选提取和 heartbeat |
| `self-analyst-file` | 文件监控及文件名、路径、大小、创建/修改时间等元数据；除解析 `.gitignore` 过滤规则外禁止读取正文 |
| `self-analyst-wiki` | 按小时、半天、日、周、双周和月聚合标题事实，维护摘要、生成账本与派生索引 |
| `self-analyst-app` | 生命周期、配置、Agent、桌面 REST/SSE 与静态 UI |
| `self-analyst-axsidecar` | OS 无障碍树查询协议；失败返回空，不负责持久化 |
| `self-analyst-desktop` | Tauri 窗口、Java 后端启动与关闭、端口握手 |

## 3. 标题采集链路

`WindowsCapture` 读取前台应用、系统窗口标题和句柄。`TitleCapture` 在窗口变化或稳定刷新时查询
UIA，将整棵树作为单次调用内的临时输入，依次尝试应用专用标题和 `Document.Name`。它返回不含
树或正文的 `TitleCaptureResult`，由 `ContentWatcher` 构造固定字段 heartbeat。

边车不可用、超时或解析失败时，系统保留窗口标题采集，不启动任何截图回退。敏感应用由
`ContextCapturePolicy` 在查询前排除。

## 4. 持久化边界

嵌入式模式以 `events.db` 为权威活动存储。连续相同 heartbeat 在 pulsetime 内更新同一活动区间，
结束时间取较大值；状态变化、采集中断或新会话新建区间。events 与导入保留独立事件语义。
事件、bucket 更新时间和 24 小时幂等回执同事务提交，失败全部回滚。回执不保存事件正文并定期回收。

旧 raw/投影双层数据经独占锁、旁路复制、待处理提交核对和完整性校验后切换，旧库作为显式清理的备份。
新格式不再提供逐心跳查询或从 raw 重建的保证。Wiki 与语义索引仍为派生数据，事件库需要独立备份。

内容事件 v2 允许 `app`、`title`、可选 `context_title/context_kind`、`title_source`、可选
`title_confidence`、`uia_chars` 和时间元数据。共享写入策略覆盖 HTTP heartbeat/events、导入和
内部存储调用。历史 v1 内容会在 watcher 启动前净化。

Wiki 只能消费标题事实；文件采集器不得读取普通文件正文、计算内容哈希或调用内容摘要/embedding；
只允许通过不跟随链接且有大小/身份校验的入口，在内存中读取监控树内 `.gitignore` 以决定路径是否排除；
规则及其编译后的 matcher 只可保留在进程内缓存，不得进入持久化、日志、错误记录或外发数据。
任何日志、异常、失败记录或备份都不得绕开相应规格保存原始输入。

### Wiki 标题事实采样

小时、半天与日摘要直接读取各自区间的完整活动事实，先由共享统计计算全量时长，再生成有界的结构化标题样本。
样本包含应用、标题、类型、来源事件 ID 和时间区间；重复标题归组，但 A → B → A 保留 A 的分离区间。
窗口样本采用扣除 AFK 后的有效区间，上下文按同应用、同系统标题的时间交集关联；无法关联的区间标为
`activityMatched=false`，仅说明观察到该标题。上下文与窗口是同一活动的不同视角，不相加计时。
内容源回退到系统标题时，先扣除被同应用、同完整标题有效窗口覆盖的重复区间，未覆盖部分仍作为观察保留；
额外的聊天、文章、文档或页面标题保持独立事实。部分匹配不会抹掉其余观察区间。

常规单次采样的标题事实预算由 `wiki.prompt.maxContentChars` 控制，默认 24000 字符，显式合法配置继续生效；
Wiki 长输入另用该值控制分块标题投影。单次采样预算计入紧凑 JSON Lines 的结构开销；窗口和新增语义上下文各预留一半，缺失一类时另一类可用全额，
未用余量回收；回退系统标题的未匹配观察只参加余量竞争。采样在统计周期的四个时间层之间轮流选取，
活动标题按有效交集参与对应时间层，纯观察仅用起点定位；层内以有效时长和渐进的应用/相似标题惩罚选择，避免短时工具
无条件挤掉主要应用的其他主题。词片只用于排序，不将不同标题合并为推断出的任务；观察时长不作为活跃权重。
每个标题最多展示四个代表区间，每区间最多四个来源 ID，并报告省略数量；标题按最多 160 Unicode code point
安全截短，完整标题身份仍用于去重。本地事实保留原始精度时间和有界来源引用；单次采样的模型输入使用短事实 ID、
短键和相对于周期起点的秒偏移区间（毫秒显示精度），避免反复展开来源 ID 及长时间戳。
固定提示词和全量统计指标位于这段标题事实预算之外；扩大预算可能增加输入 token 和费用。

常规单次采样携带候选数、选中数及省略区间数量，不应把样本当作完整活动列表，也不能仅凭标题声称任务已完成。
采样元数据保存于摘要指标的 `titleSampling` 中，被任务引用的有界证据目录保存在派生指标的 `evidenceFacts` 中。
短摘要使用一次模型调用，长输入按有界树形生成；不依赖 Python/LlamaIndex，也不自动重算历史。

新生成 Wiki 的自然语言字段（summary、primaryTask、任务标题/描述/证据）只叙述活动、项目和技术主题。
AFK 覆盖与活动时长留在内部判断数据及结构化 metrics/sourceCoverage，不在任务文案中复述。
覆盖不足时任务片段置信度最多为 medium，模型使用“涉及/查看/相关开发”等谨慎描述，不推断任务完成。
提示词不再要求模型生成 metrics，本地指标仍按原口径填充；常见统计播报泄漏会返回固定校验错误并进入
现有退避重试，单次生成不额外请求模型修补，也不通过删除句子拼接摘要。技术主题中的 AFK 采集器名称、
连接超时参数等保持可描述。旧摘要、看板独立统计详情及其估计说明继续保留。

### Wiki 主题组织、周期预算和恢复

长输入从完整白名单事实开始规划，不先按一次请求的采样上限丢掉后续事实。`WikiTopicPlanner` 结合全局
词面候选、来源和时间层分配有界分块及归并调用；词面归组只是组织线索，不自动证明同一真实任务。
`WikiTopicProtocol` 区分 DIRECT、LEAF、MERGE、FINAL 阶段：叶卡保存全部 `memberInputIds` 和少量
`representativeFactIds`；上层通过 `sourceTopicIds` 合并卡片，本地沿成员链保留原事实与应用。根汇总优先
压缩卡片投影，不因减少一条展示引用而连带删除整项主题；预算不可行时明确记录省略和未归类项。
应用列表及展示 evidence 均从已验证引用本地派生，模型兼容字段不能覆盖事实来源。未知 ID、结构和长度
错误仍被拒绝，标题中的动作字样不直接等同于用户执行了动作，跨层合并不能提升原证据的断言强度。

单棵树由 `wiki.summary.maxCalls=6`、`wiki.summary.maxRequestChars=32000` 和总超时约束；应用通过
`PlainTask` 租约固定整次生成的模型。另有按层级、周期起止和时区标识的终身累计额度：
`wiki.summary.periodMaxCalls=12`、`wiki.summary.periodMaxTokens=256000`，键不含输入、模型、版本或
执行日期。更换模型、补齐输入、跨日及重启均不能获得一份新额度；全局 `UsageMeter` 仍独立按执行日计量。

`WikiGenerationStore` 使用独立 `{memory.dir}/wiki-generation.db`、专用连接和 `BEGIN IMMEDIATE`
短事务原子预留一次调用及完整输入 token 估计加 `wiki.summary.outputTokenReserve=4096`。
配置不可用或全局预算在发出前阻断时不记调用；发出后失败、超时、取消及崩溃不确定请求占用额度。
真实 usage 通过 call ID 幂等结算，未知结果保留预留，迟到的已知 receipt 可替换保守估计。
正式 plain 固定温度 0.2，不发送输出上限；真实 usage 可超过预留，因此额度控制后续准入，不承诺账单硬上限。
plain 显式设置 SDK `maxAttempts=1`，保留原有超时及其他执行默认值；429、5xx 和传输失败不会在一次
周期预留下隐式重复发送请求，后续尝试须重新经过外层准入。一次模型 receipt 因而对应一次 SDK HTTP 尝试。
全局用量与周期账本没有跨库原子事务，不能当作全局所有并发模型调用的统一持久配额。

已验证的叶、归并及最终结果保存为检查点，不保存完整 prompt、原始模型响应或凭据。检查点键包括规范化
输入、稳定模型指纹、提示词/管线/事实/统计/日历版本及计划参数；模型指纹包含 endpoint、model、实际
plain 选项、system prompt 和语言的散列，不包含 API key 或进程内 revision。凭据轮换和重启可复用兼容
结果，配置 revision 仅负责唤醒配置暂停。最终检查点先持久化，再幂等发布到 `llm-wiki.db`；跨库发布失败
只重试本地保存。发布满 7 天的检查点在后续清理时回收，未发布检查点不按时间删除，调用及周期账本不随
检查点清理归零。独立库使用 schema 1，只读数据准入拒绝未知或损坏格式，不擅自替换为空库。

entry 保留 PENDING、SUMMARIZED、FAILED、SKIPPED 四状态，结构化 generation 信息区分周期/全局预算、
模型配置、网络/超时、质量拒绝、输入不可行及本地发布失败。暂停条目排除在普通到期队列之外；查询和
历史时间轴继续展示本地统计，并返回暂停原因与消耗。周期额度耗尽不随次日恢复，用户显式调高相应额度并
重启后，只有剩余额度可满足下一次预留才恢复，旧消耗不清零。关闭先拒绝新任务，再停止 worker、等待已发
请求及检查点结算完成，最后 flush 用量并关闭数据库，避免取消后的回调向已关闭资源写入。

## 5. 生命周期

`AppSession` 的主要顺序是：取得数据根锁与格式准入 → 受控事件迁移/恢复 → 开放权威事件库 →
内容策略准备 → watcher 与派生工作。启动失败阻止采集和端口发布；停止时关闭采集器、事务写入器及数据库。
事件服务持有独立目录锁至关闭，生产数据根锁保守保留至 JVM 退出。

Windows 登录自启动由 Tauri 壳管理当前用户 Run 入口 `SelfAnalystDesktop`，注册桌面 EXE 的
`--autostart` 启动方式。首次使用默认关闭；自动启动复用后端生命周期并从创建时隐藏主窗口。
单实例 mutex 与固定窗口唤起事件按当前用户、登录会话隔离，手动第二实例请求恢复已有窗口，自动第二实例
静默退出；窗口尚未就绪的唤起请求会保留到创建完成。系统注册状态不进入 Java 用户配置。

不存在声音 watcher、声音控制器或 OCR 引擎生命周期。

## 6. 配置兼容

`SupportedKeys` 是现行配置白名单。`DeprecatedKeys` 保存已移除 OCR/声音键的墓碑：旧文件加载时
静默忽略，桌面配置响应不暴露，结构化保存不写回。此兼容层不创建旧模块依赖。

配置解析区分用户覆盖、环境变量兜底和当前运行快照。ConfigApplicationService 统一协调应用内的
raw、结构化及 Agent 工具保存；候选模型准备和写盘成功后发布新版本，失败则保留旧文件与运行实例。
文件设置专用接口参加同一持久化协调，并继续报告实际已应用的采集配置。

SelfAnalystAgent 保持稳定的工具、会话存储和 UsageMeter，LlmRuntimeManager 管理可替换的聊天、
plain 和压缩执行资源。聊天取得应用级 gate 后固定一轮租约；独立摘要在任务开始时取得租约。旧模型
在最后一个使用者离开后释放，模型切换不顺带应用待重启参数。缺少模型配置时，本地服务继续运行，
Wiki LLM 工作保持可重试，填写密钥后恢复。关闭先拒绝新的配置与模型工作，再等待租约退出并释放资源。

GET /desktop/config/effective 提供脱敏的已保存值、来源、实际运行值和各组件应用结果；raw 配置接口
继续用于受保护的原文编辑。LLM 可热更新的键、其它键的重启策略由 ConfigPolicy 统一声明。
本期不监听外部配置文件修改，也不热更新 Embedding 客户端或迁移索引。

### 独立模型设置

`com.selfanalyst.llm.settings` 提供单连接设置服务、只写凭据操作、预设目录和有界 HTTP 探测；
桌面控制器通过 `/desktop/llm-settings` 及其子路径提供专用 API。`LlmSettingsRepository` 的 TOML
适配器进入 `ConfigApplicationService` 的同一提交锁，严格读取最新原文，由 `LlmTomlEditor` 定向
编辑并重新解析比对，再复用已有候选资源准备及版本发布。不增加独立进程或 Maven 模块。

`desktop-ui/llm-settings.js` 管理独立草稿和请求代次，前端不读取 raw 文本来构建模型表单。
配置窗口默认显示模型设置；高级配置保留原文编辑与键定位。切换会确认脏态，运行状态刷新不替换草稿。
密码只存在于暂时输入和后端内部连接对象，普通查询仅返回凭据状态与来源。测试与发现是显式诊断，
不携带业务内容，不自动保存；超时、大小上限和禁止重定向限制远端响应。详见[模型设置指南](llm-settings.md)。

## 7. 发布结构

```text
dist/
├── SelfAnalyst.exe
├── self-analyst-app.jar
└── portable.marker             # 用户可选创建，启用程序旁数据

dist-portable/
├── SelfAnalyst.exe
├── self-analyst-app.jar
├── runtime/                    # jlink JRE
└── portable.marker             # 标准 ZIP 不预置

artifacts/
├── SelfAnalyst-portable.zip
├── SelfAnalyst-portable.zip.sha256
├── SelfAnalyst_0.5.0-beta.1_x64-setup.exe
└── SelfAnalyst_0.5.0-beta.1_x64-setup.exe.sha256
```

NSIS 安装包把后端 JAR、jlink runtime 和安装布局标记作为 Tauri resource 安装。桌面壳识别该标记后，
从 resource 目录启动后端，但把工作目录切换到当前用户应用数据目录；因此应用升级或卸载不会把用户
数据库与配置当作安装文件处理。免安装包默认使用同一用户运行目录，只有 EXE 同级存在有效
`portable.marker` 时才以程序目录为工作目录。Java 在业务初始化前持有 `data/app.lock` 并检查格式；
没有标记的兼容旧数据经只读验证后原地登记，不搬运数据。生产锁保留至 JVM 终止，防止后台写入
尚未结束时提前释放。详见[运行数据指南](runtime-storage.md)。

accessibility sidecar 随内容模块资源打包并按平台释放。所有发布结构都没有 `tools/PaddleOCR-json`、
`tools/whisper` 或声音模型。

## 8. 文档生成与另存为

`self-analyst-app` 的 `com.selfanalyst.document` 提供有界结构化渲染、数据快照导出、文件发布和版本管理。DesktopServer 创建会话 writer 后注入文档服务及 Agent 工具；工具身份从本轮 RuntimeContext 注入，不接受模型指定的会话归属。现有 ReActAgent 持有工具集副本，因此当前实例与后续模型版本的工具模板均注册文档工具。

文档元数据使用 chat.db 的附加表，正文和生成源独立保存于 `memoryDir/documents/`。完整文件通过格式检查后才发布 READY，发布失败、取消或重启恢复清理半成品；删除会话复用删除 intent，用户另存副本不参与清理。直接导出使用只读快照和磁盘行集，原始记录不进入模型结果；原始数据导出仅开放给受管桌面聊天，普通事件查询工具继续读取派生投影。

桌面文件接口位于 `/desktop/chat/sessions/{id}/documents`，统一要求受管认证。Tauri 保存命令只接收会话和文件标识，使用系统对话框选目标，再校验来源、长度与摘要，最后替换目标文件。浏览器使用 cookie 认证下载。生成和保存的用户行为见[文档生成指南](document-generation.md)。

## 9. 验证

摘要使用共享标题事实目录：窗口、上下文和当前窗均经 AFK 交集及确定性预算采样。中文 n-gram、
按来源的时间覆盖约束、首尾标题和最长区间代表改善主题保留；观察跨度不充当活动时长。
新任务携带 evidenceFactIds 与 observed/inferred，写入前验证结构与引用，再本地派生应用和证据目录；
旧 TaskSegment 缺省为 legacy，不伪造来源。引用正确不等于动作完成，常见成果断言仍受限制。

WikiSummaryPipeline 的 `generation` 指标分别记录 `processedFacts/omittedFacts`（输入处理）、
`assignedFacts/unresolvedFacts`（模型成员归类）、`topicCardsPresented/omittedTopicCards/unresolvedTopicCards`
（卡片合并传递）和 `finalReferencedFacts`（展示引用）。`assignmentMeaning` 明确这些归属是模型分类，
不是语义验证；少量展示引用不代表其余成员丢失，结构传递完整也不证明自然语言无遗漏或无依据断言。
确定性规划、预算、引用链和恢复由自动回归验证，真实主题聚合仍需独立人工审阅。最终时长等指标来自全量本地统计。
周/月输入带子周期日期、任务和 child 引用，sourceEntryIds 记录实际子条目。看板当前窗保持一次小请求、
5 秒超时、主题指纹缓存及本地降级，不因打开页面重算历史。评测默认离线，显式正式模式复用 PlainTask、
隔离用量及同一 system prompt，不使用临时聊天温度或输出上限作为正式基线。方法见
[摘要质量评测指南](summary-quality-evaluation.md)。

- Java：`mvn test`
- Rust sidecar：`cargo test --manifest-path self-analyst-axsidecar/Cargo.toml`
- 便携发布：`.\scripts\build-portable.ps1`
- NSIS 发布：`.\scripts\build-installer.ps1` 后运行 `.\scripts\check-installer.ps1`
- 可执行 JAR：`.\scripts\check-packaged-jar.ps1`
- 数据边界：内容策略、迁移、标题提取和配置墓碑的自动化测试
