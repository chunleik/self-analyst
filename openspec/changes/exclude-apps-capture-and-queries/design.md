## Context

前台活动由两个进程内采集器写入嵌入式事件服务：`self-analyst-events` 的 `WindowWatcher`（`watcher-window_*` 桶，经 `Watcher` 基类发送 heartbeat，`collect()` 返回 `null` 时本轮不发送）和 `self-analyst-content` 的 `ContentWatcher`（`watcher-content_*` 桶，采集线程发布 `Snapshot`，heartbeat 线程定时发送）。两者都以进程可执行文件名（如 `PotPlayerMini64.exe`）作为 `app`。

模型可见的隐私策略集中在 `self-analyst-wiki` 的不可变类 `WikiPrivacyPolicy`，由 `AppSession`、`DesktopServer` 在启动时用 `config.wikiExcludeApps()`/`wikiExcludeSites()` 分别构造多份并注入 `WikiFactBuilder`、`SummaryService`、`OntologySources` 等。会话 Agent 的 `EventQueryTools` 直接把事件服务 HTTP 响应交给模型，`DocumentDataSources` 的 `projection` 导出逐行写入事件 `data`，都未经过该策略。

配置保存统一经过 `ConfigApplicationService.commit`；除 LLM 外，`ConfigPolicy.requiresRestart` 把未列入 `DYNAMIC` 的键都报告为需重启，`running` 记录运行值。文件采集设置已有“专用接口保存 → 立即应用 → `markApplied`”的先例。配置弹窗的页签由 `ui.js` 的 `switchConfigView` 管理，Neo4j、运行数据页签各自以独立脚本挂载到 `config-grid`。

## Goals / Non-Goals

**Goals:**
- 采集排除在采集器源头生效，不改动事件服务的通用 heartbeat 接口。
- 所有模型可见路径读取同一份运行时隐私快照，保存后无需重启即生效。
- 任意保存入口（隐私页签、raw 编辑器、结构化接口）都走同一个热应用路径。

**Non-Goals:**
- 不删除或改写已记录的历史事件，不重新生成已有摘要，不改写会话历史中的工具结果。
- 不过滤桌面看板、时间线等只对本机用户展示的数据。
- 不过滤 `listBuckets`、`getServerInfo`、`getSettings`。
- 不提供按时段暂停或定时恢复。

## Decisions

1. **运行时隐私快照：`PrivacyRuntime`（app 模块）。** 持有 `AtomicReference<Snapshot>`，`Snapshot` 包含采集排除集合与模型可见 `WikiPrivacyPolicy`（应用为两个列表并集，网站为 `excludeSites`）。对外提供 `Predicate<String> captureExcluded()`（每次调用读取当前快照）和 `Supplier<WikiPrivacyPolicy> modelPolicy()`，以及 `apply(String collectionApps, String wikiApps, String sites)` 原子替换。备选方案是让 `WikiPrivacyPolicy` 内部可变，但它在 wiki 模块被当作值对象使用、测试众多，改为可变会扩大影响面，因此不采用。
   - wiki 模块消费者（`WikiFactBuilder`、`WikiWorker`）与 `SummaryService`、`OntologySources` 增加接收 `Supplier<WikiPrivacyPolicy>` 的构造器，原固定策略构造器包装为常量 supplier 保留兼容；每个时间块或每次请求开始时取一次快照，保证单次处理内规则一致。
   - `DesktopServer` 缓存指纹改为使用快照内容，配置变化后看板当前窗缓存自然失效。
   - 实施审查发现行为建议把应用名嵌入证据后传给模型。保留本机建议与统计的原有载荷，另构建同一快照过滤后的模型证据；不得直接过滤本机展示数据。
   - 摘要修复的一次请求固定使用开始时的隐私快照，并传递至事实读取、用户确认内容校验与生成步骤；避免一次请求内重复读取 supplier 而混用热更新前后的规则。下一次请求使用新规则，不重写已生成摘要。以上两点于实施时经用户确认补充，保持既有 AtomicReference 快照设计。

2. **采集排除放在采集器，而不是事件服务 heartbeat 入口。** 事件服务是通用兼容 API，入口丢弃会让服务端依赖应用配置，也会让 `ContentWatcher` 投递状态误判。
   - `WatcherManager` 接收 `Predicate<String>`（默认永不排除）传给 `WindowWatcher`：命中时 `collect()` 返回 `null` 并清空 `lastApp`/`lastTitle`，使切回时以当前时间开始新事件。
   - `ContentWatcher` 新增带谓词的构造器：采集线程命中时 `latest = null` 并跳过无障碍查询；heartbeat 线程发送前的前台一致性检查保证切换瞬间不会续写旧快照。
   - 谓词每轮读取最新快照，因此热切换在一个轮询周期内生效，无需重建线程。

3. **统一热应用：配置提交成功后回调。** `ConfigPolicy` 新增 `PRIVACY` 集合（三个键），`requiresRestart` 排除它们。`ConfigApplicationService` 新增 `attachPrivacy(Consumer<Config>)`；`commit` 在文件写入成功后、返回前用提议快照调用回调，再把三个键写入 `running`。回调只做原子替换，不会失败；写入失败时不调用，运行规则保持原值。这样 raw、结构化与隐私页签共用同一路径，无需各自 `markApplied`。

4. **隐私页签后端：`DesktopPrivacyController`。**
   - `GET /desktop/privacy` 返回 `{apps:[{app, mode:"capture"|"model"}], sites:[...], candidates:[{app, seconds}], candidatesError?}`。候选通过 `EventStore.queryIntersecting` 读取本机窗口桶近 30 天事件，按 `app` 累计 `duration`（不扣 AFK，排除空白与 `unknown`），降序取前 100，并剔除已在清单中的应用；读取失败时返回空候选和错误码。
   - `PUT /desktop/privacy` 接收 `{apps:[{app, mode}], sites:[...]}`，校验应用名非空、无逗号分号、去重（不区分大小写，冲突以 `capture` 为准），再调用 `ConfigApplicationService.update` 写入三个键；成功返回最新 `GET` 载荷与 `applied: true`。

5. **隐私页签前端：`privacy-settings.js` + `privacy-settings.css`。** 在 `config-view-tabs` 增加 `data-config-view="privacy"`，`switchConfigView` 识别该视图并挂载到 `config-grid`，脏状态接入既有 `state.configDirty` 放弃确认。添加控件为带 `datalist` 风格过滤的输入框加下拉列表（键盘上下选择、回车添加），新增应用默认模式“仅对模型隐藏”；每行一个原生 `<select>` 切换模式和一个移除按钮；网站为输入后回车添加的 chip。文案键加入 `locales/zh.json`、`locales/en.json`。

6. **`EventQueryTools` 递归数组过滤。** 新增 `EventPrivacyFilter`：解析响应为 `JsonNode`，深度优先遍历，数组元素为含 `data` 对象的事件且 `policy.excluded(app, title, url_host, private_browsing)` 或 `excludedTitle(context_title)` 为真时移除，再序列化并按原预算截断；非 JSON 成功响应返回结构化错误。构造器接收 `Supplier<WikiPrivacyPolicy>`，每次调用取当前快照。改写 AQL 注入过滤无法覆盖所有查询形态，因此不采用。

7. **导出过滤与计数。** `DocumentDataSources` 接收 supplier，在 `prepare` 开始时取一次快照；`projection` 分支命中则计数跳过，元数据写入 `privacyFilteredCount`，`complete` 保持 `true`。

## Risks / Trade-offs

- [AQL 聚合数值包含隐藏时长] → 规格明确允许；工具描述补充说明。
- [可执行文件名不一致（32/64 位、便携版）] → 候选下拉直接列出实际采集到的进程名；文档提示可同时添加多个变体。
- [采集排除期间活动统计出现空档] → 预期行为；页签文案与文档说明这段时间不计入活跃时长。
- [递归过滤误删非事件对象] → 只移除位于数组中、含 `data` 对象且命中策略的元素，单元测试覆盖嵌套数组、非事件对象与空策略。
- [候选聚合读取 30 天事件的开销] → 仅在打开隐私页签时执行，按需聚合且只返回前 100；若超出既有查询上限则返回部分候选并标注。
- [热切换与正在执行的摘要/导出并发] → 单次处理开始时取快照，处理过程中规则不变；下一次处理使用新规则。

## Migration Plan

无数据迁移。新键默认空，未配置时行为不变；已有 `wiki.privacy.*` 值在隐私页签中显示为“仅对模型隐藏”。回滚只需删除配置或回退版本，历史事件不受影响。
