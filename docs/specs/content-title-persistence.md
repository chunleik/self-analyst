# 上下文标题最小化持久化规格

> 状态：已实现
> 规格前缀：`SPEC-CTP-*`
> 适用模块：`self-analyst-content`、`self-analyst-aw`、`self-analyst-wiki`、`self-analyst-app`、`self-analyst-file`

## 1. 目标和边界

### SPEC-CTP-001：读取域与持久化域

- 采集器允许在当前调用的内存中查询完整 UIA 树、UIA 文本和其他识别输入。
- 原始窗口正文不得进入 SQLite、Lucene、日志、普通缓存文件、迁移备份或内容 heartbeat。
- 持久化域只允许系统窗口标题、应用内上下文标题、标题来源、置信度、应用标识和时间/计数元数据。
- 临时原文不得出现在可持久化事件类型的属性中。

### SPEC-CTP-002：派生数据

- Wiki 可以保存由标题事实生成的摘要、主要任务和任务片段。
- 文件模块可以临时读取文件正文，但 `file-watch.db` 和文件语义索引只能保存路径、哈希、摘要、主题、状态和向量等派生结果。
- 派生失败信息和日志不得包含原始输入或完整 prompt。

### SPEC-CTP-003：本期非目标

- 本规格不改变 OCR 识别算法、截图方式、OCR 调试样本和完整窗口 OCR 开关。
- 本规格不改变音频录制、转写、音频事件和音频 API。
- OCR 和音频的持久化策略由后续独立规格处理。

## 2. 内容事件 v2

### SPEC-CTP-010：Bucket 身份

以下任一条件成立时，事件必须应用内容事件 v2 策略：

- bucket ID 以 `aw-watcher-content_` 开头；
- bucket `client` 等于 `aw-watcher-content`。

### SPEC-CTP-011：字段白名单

内容事件 `data` 只允许下列字段：

| 字段 | 类型 | 必需 | 约束 |
|------|------|------|------|
| `schema_version` | integer | 是 | 固定为 `2` |
| `app` | string | 是 | 单行、最多 260 个 code point，可为空字符串 |
| `title` | string | 是 | Win32/系统窗口标题；单行、最多 1024 个 code point，可为空字符串 |
| `context_title` | string | 否 | 单行、最多 200 个 Unicode code point |
| `context_kind` | string | 条件必需 | `chat/article/document/page/unknown` |
| `title_source` | string | 是 | `window/uia_document/uia_context/ocr_title` |
| `title_confidence` | string | 否 | `high/medium/low` |
| `uia_chars` | number | 否 | 非负，仅为诊断计数 |
| `ocr_chars` | number | 否 | 非负，仅为诊断计数 |

`context_kind` 和 `title_confidence` 仅可在 `context_title` 存在时出现。禁止字段包括
`text_content`、`uia_text`、`ocr_text`、`raw_text`、`raw_tree`、`content`、`body`、截图和可还原正文的节点列表。

### SPEC-CTP-012：标题候选

- 应用专用上下文标题优先于 UIA Document 标题，UIA Document 标题优先于系统窗口标题。
- 微信上下文标题可以通过完整 UIA 文本识别，但最终只输出一个结构化候选。
- 微信聊天标题使用 `context_kind=chat`；微信 Document 标题使用 `context_kind=article`。
- 空值、通用应用名、中英文通用控件、纯 URI/URL、多句正文、换行文本及超过 200 个
  code point 的值不得成为候选。
- OCR 只有在标题条模式且识别结果唯一、明确时才能产生 `ocr_title` 候选；
  `ocr.title-strip-height=0` 的完整窗口 OCR 结果不得成为持久化标题候选。
- 无可靠候选时省略 `context_title`，不得把正文前缀作为回退标题。

### SPEC-CTP-013：内存边界

- 原始 `ContentResult` 只能在采集调用内临时存在。
- `ContentWatcher.Snapshot` 必须保存不含正文的标题投影。
- heartbeat 数据必须从固定字段逐项构造，不能透传通用 Map 或 OCR 样本 ID。
- heartbeat 返回非 2xx 或发生网络错误后，采集器运行状态必须变为 `degraded`；后续成功可恢复。

## 3. 服务端写入策略

### SPEC-CTP-020：共享校验

- 内容事件策略必须覆盖 heartbeat、单条/批量 events、数据导入和内部 `EventStore` 调用。
- 校验必须发生在共享写入边界，并按 bucket 身份选择性启用；不得全局禁止其他 bucket 的 `text` 字段。
- 批量写入必须先校验整批事件，字段策略失败时不得部分入库。
- 数据导入必须在创建 bucket 和写入事件前完成内容事件预检。
- 所有 bucket 创建入口必须使用统一的安全 ID 规则；空事件导入也不得绕过校验。
- HTTP 事件与 heartbeat 写入要求 bucket 已存在，避免先写原文再创建内容 bucket。
- 创建内容 bucket 时必须校验同 ID 的历史孤儿事件；存在 v1/违规事件时拒绝接管。

### SPEC-CTP-021：错误响应

- 违规内容事件通过 HTTP 写入时返回 `422`。
- 响应和日志只允许包含禁止字段名，不得回显字段值或完整请求体。
- 旧 v1 采集器不能通过继续写入 `text_content` 获得兼容；兼容由历史迁移完成。

## 4. 历史数据迁移

### SPEC-CTP-030：迁移内容

- 启动时扫描所有内容 bucket 的历史事件。
- 迁移可在删除原文前临时使用旧 `text_content` 补提取微信对话标题。
- 迁移结果必须符合 v2 白名单，并删除所有未知字段和原始正文。
- 迁移必须幂等；已为 v2 的事件再次扫描不得被改写。
- 成功迁移后保存事务快照内的全局 `MAX(events.id)` 扫描水位；后续启动只过滤更高 ID 的新增
  事件，避免反复扫描窗口/音频等非内容尾部，旧版本后来写入的 v1 内容事件仍必须被捕获。
  高水位只能与迁移标记一起在物理净化成功后提交。
- 逻辑净化事务必须先持久化 `needs_compaction` 和待提交高水位；物理净化或旧副本清理失败时
  脏标记不得清除。下次启动即使 `sanitized=0` 也必须重试物理净化，成功后才能推进高水位。

### SPEC-CTP-031：物理净化

- 净化事务启用 SQLite `secure_delete`。
- 存在被净化事件时，提交后执行 WAL checkpoint、WAL 截断和 `VACUUM`。
- 默认迁移不得创建包含原始正文的普通备份。
- 旧版内容 bucket 独立数据库、`aw.db.pre-legacy-migration-*` 明文备份和已完成后的
  `aw.db.migrating*` 工作文件必须删除，不能作为正文副本长期保留。
- 旧库删除目标必须同时通过安全 bucket ID、规范化父目录和主库保留文件校验；任何校验失败
  必须终止迁移并降级，不能尝试目录外删除。
- 迁移标记为 `content-events-title-only-v2`，只有完成逻辑和物理净化后才能写入。

### SPEC-CTP-032：失败降级

- 迁移必须早于上下文标题 watcher 和 Wiki worker 启动。
- 迁移失败时，窗口/AFK/音频和其他桌面能力可以继续运行。
- 上下文标题 watcher 和 Wiki worker 必须禁用。
- `/desktop/status` 必须把 `collectors.contextTitle` 报告为 `degraded`，并在
  `contentPersistence` 中提供不含原文的失败状态。
- 桌面状态栏必须显式读取 `contentPersistence`；即使窗口/AFK 正常，迁移失败也必须显示橙色
  降级状态和安全错误摘要。

## 5. Wiki 和文件消费者

### SPEC-CTP-040：Wiki 标题事实

- `WikiFactBuilder` 不得读取 `text_content`。
- 内容事件采样优先使用 `context_title`，为空时回退到 `title`。
- 采样按 `(app, effectiveTitle, contextKind)` 去重，并受总字符预算约束。
- Wiki prompt 使用“应用内标题样本”，不得包含“屏幕内容片段”或原始正文。
- 标题型 Wiki prompt 版本为 `wiki-v3` 或更高。

### SPEC-CTP-041：文件持久化

- `file-watch.db` 不得新增原始文件正文、截断正文或完整 prompt 字段。
- 文件语义索引只允许相对路径、摘要、主题及必要元数据。
- 测试必须使用唯一秘密标记证明原始文件正文没有进入数据库、WAL 或 SHM。
- 文件处理失败时，`last_error` 只能保存固定错误码和异常类型；日志不得输出异常 message，
  防止 LLM 或解析器回显 prompt/正文。
- Embedding 输入和 Lucene 存储必须分别验证只包含相对路径、摘要和主题。

## 6. 配置和状态兼容

### SPEC-CTP-050：配置语义

- 为避免破坏现有用户配置，继续读取 `aw.collection.content` 和
  `aw.collection.content.pollMs`。
- 两个键的新语义分别为“是否识别并保存上下文标题”和“上下文标题心跳间隔”。
- 用户可见说明不得再声称该开关会持久化窗口正文。

### SPEC-CTP-051：状态兼容

- `/desktop/status.collectors.contextTitle` 是新的规范字段。
- 一个兼容周期内同时返回 `collectors.content`，其值必须与 `contextTitle` 相同。
- 桌面前端优先读取 `contextTitle`，旧字段仅作为兼容回退。
- `contentPersistence.schemaVersion` 固定为 `2`。

## 7. 测试规格

### SPEC-CTP-T01：采集端秘密标记

给 UIA 文本注入唯一秘密标记。标题提取可以命中上下文标题，但 Snapshot、heartbeat Map 和序列化 JSON 中不得出现秘密标记、`text_content` 或 OCR `sample_id`。

### SPEC-CTP-T02：写入策略

- 合法 v2 内容事件可以入库。
- prefix 或 client 标识的内容 bucket 写入 `text_content` 时必须失败。
- 同样的数据写入音频等非内容 bucket 时保持既有行为。
- 导入预检失败时不能创建 bucket 或写入任何事件。

### SPEC-CTP-T03：迁移

- 旧微信正文应补提取对话标题并删除正文。
- 非标准 ID、但 client 为内容采集器的 bucket 同样必须迁移。
- 重复执行迁移时 `sanitized=0`。
- 关闭数据库后，DB/WAL/SHM 的字节扫描中不得存在秘密标记。
- 模拟逻辑净化提交后物理压缩失败；重启必须保留脏状态、再次执行物理净化并在成功后推进水位。
- 大量非内容事件位于尾部时，成功扫描水位必须推进到事务快照的全局最大事件 ID。

### SPEC-CTP-T04：Wiki 和文件

- Wiki 标题样本不得包含历史 `text_content`。
- Wiki prompt 不得出现正文秘密标记。
- 文件索引完成后，`file-watch.db`、WAL 和 SHM 不得包含原始文件秘密标记。
- 文件摘要失败回显 prompt 时，`last_error`、数据库和日志不得包含秘密标记。
- File Embedding 请求文本与 Lucene 文件不得包含原始文件秘密标记。

## 8. 追溯矩阵

| 规格 ID | 文件/组件 | 验证 |
|---------|-----------|------|
| `SPEC-CTP-001..003` | `ContentResult`、`TitleCaptureResult`、`FileWatchStore` | 模块测试、字节扫描 |
| `SPEC-CTP-010..013` | `ContextTitleCandidate`、`ContextTitleExtractor`、`ContentWatcher`、`ContentEvent` | `ContentWatcherTest`、`ContextTitleExtractorTest` |
| `SPEC-CTP-020..021` | `ContentEventPolicy`、`EventStore`、`HeartbeatController`、`EventController`、`DataImporter` | `ContentEventPolicyTest`、`DataImporterContentPolicyTest` |
| `SPEC-CTP-030..032` | `ContentEventV2Migration`、`AppSession`、`DesktopStatusController` | `ContentEventV2MigrationTest`（增量水位、脏压缩恢复、路径边界）、`DesktopStatusControllerTest` |
| `SPEC-CTP-040` | `WikiFactBuilder`、`WikiSummarizer`、`WikiWorker` | `WikiFactBuilderTest`、`WikiSummarizerTest` |
| `SPEC-CTP-041` | `FileWatchStore`、`FileIndexWorker`、`FileEmbeddingWorker`、`FileSemanticIndex` | `FileIndexWorkerTest`、`FileEmbeddingWorkerPrivacyTest` |
| `SPEC-CTP-050..051` | `SupportedKeys`、`DesktopStatusController`、`desktop-ui/ui.js` | 配置测试、状态测试、Node UI 测试 |
