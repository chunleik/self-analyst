# self-analyst-file SDD 规格说明书

> 目录文件监控 + LLM 摘要 + 语义索引 + 时间轴关联模块。
> 状态：**已实现**（`self-analyst-file` 模块）。

---

## 1. 模块标识

| 属性 | 值 |
|------|-----|
| 模块名 | `self-analyst-file` |
| 版本 | 1.0.0 |
| 类型 | Java 21 库模块 |
| 默认开关 | `file.watch.enabled=false` |

---

## 2. 架构契约

### SPEC-FILE-001: 模块依赖

- 复用 wiki 模块的 `EmbeddingClient` 接口；LLM client / EmbeddingClient 由 app 层注入
- 新增 `org.apache.pdfbox:pdfbox`（PDF）、`org.apache.poi:poi-ooxml`（Office）
- 复用父 BOM 的 `lucene-*`（向量索引）、`sqlite-jdbc`、`jackson`
- **不依赖** `self-analyst-aw`：时间轴 heartbeat 走 HTTP（与标题采集器一致），非 `EventStore` 直连
- **SPEC-FILE-001a**：`poi-ooxml` 传递引入 `log4j-api`（POI 用 log4j2 API）。本仓库日志栈为 slf4j+logback；仅 `log4j-api`（无 `log4j-core`）不冲突，POI 日志默认 no-op，需要并入 logback 时可加 `log4j-to-slf4j` 桥接（可选）

### SPEC-FILE-002: 数据流

```
watch 目录变更 → FileWatcher (NIO WatchService)
  → PathFilter 过滤 → FileWatchStore.upsertPending() → POST heartbeat (HTTP)

FileIndexWorker (周期)
  → 取一条 PENDING → FileContentExtractor.extract()
    → truncate(maxContentChars) → FileSummarizer.summarize(LLM)
      → FileWatchStore.updateIndexed() → FileEmbeddingWorker.enqueue()
        → embed → FileSemanticIndex (Lucene KNN)

Agent → FileTools → FileSemanticIndex / FileWatchStore 查询
```

### SPEC-FILE-003: 职责边界

- **FileWatcher**：仅处理运行期间的增量变更（事件驱动）
- **FileIndexWorker**：启动时做一次对账扫描，之后周期处理 PENDING 队列
- **SPEC-FILE-003a**：启动扫描发现的已有文件**不**补发 heartbeat，因此时间轴只覆盖运行期间的变更（历史补发列为后续增强）

### SPEC-FILE-004: 单次变更处理时序

检测到文件变更后分两条解耦路径，通过 SQLite 的 PENDING 队列衔接：

**路径 A — 实时捕获（FileWatcher 线程，事件驱动，不读内容/不调 LLM）**

```
ENTRY_CREATE/MODIFY(文件)
  → PathFilter（命中排除 → 直接丢弃）
  → 去抖判定（SPEC-FILE-019：未静默则推迟，不入队）
  → upsertPending(path)  // status=PENDING，幂等
  → 发 heartbeat（HTTP，节流后）→ AW 时间轴
ENTRY_DELETE → markDeleted(path)  // status=DELETED，不摘要
```

**路径 B — 异步处理（FileIndexWorker 线程，周期取一条 PENDING）**

```
取一条 PENDING
  → (size,last_modified) 预筛 → 必要时算 SHA-256
      ├─ hash 未变 → 跳过（不重新摘要）
      └─ hash 变 / 新文件:
          extract → truncate → summarize(LLM)
          → updateIndexed（status=INDEXED, 写 summary+新 hash）
          → enqueueEmbedding → 向量入 FileSemanticIndex
  失败 → markFailed（status=FAILED, 写 next_retry_at 退避）
```

- **SPEC-FILE-004a**：状态流转 `PENDING → (INDEXED | SKIPPED | FAILED)`；`FAILED` 到 `next_retry_at` 后重回处理；`DELETED` 为终态（再次出现则新 upsert 回 PENDING）
- **SPEC-FILE-004b**：路径 A 只登记意图（轻量），路径 B 才读文件——保证 watcher 线程不被 IO/LLM 阻塞
- **SPEC-FILE-004c**：同一 path 在 worker 处理前被多次 upsert，仍只是一行 PENDING，处理时读**当前最新内容**算一次 hash → 一段时间内多次编辑只摘要一次

---

## 3. 组件规格

### SPEC-FILE-010: FileWatchStore（SQLite）

库文件：`{memory.dir}/file-watch.db`。`file_index` 表列：
`id`, `absolute_path` (UNIQUE), `relative_path`, `watch_root`, `extension`, `size_bytes`,
`file_hash` (SHA-256), `last_modified` (ISO-8601), `first_seen_at`, `last_indexed_at`, `status`
(PENDING|INDEXED|FAILED|SKIPPED|DELETED), `summary`, `main_topics_json`, `model`,
`prompt_version`, `retry_count`, `next_retry_at`, `last_error`, `created_at`, `updated_at`。

- **SPEC-FILE-010a**：开启 `PRAGMA journal_mode=WAL` + `PRAGMA busy_timeout`
- **SPEC-FILE-010b**：FileWatcher 与 FileIndexWorker 跨线程写入，所有写操作 `synchronized`（或单写线程 + 队列）；不得出现 `SQLITE_BUSY`
- **SPEC-FILE-010c**：时间轴查询走 `WHERE last_modified BETWEEN ? AND ?`
- **SPEC-FILE-010d**：变更检测先比 `(size, last_modified)` 廉价预筛，命中才算 SHA-256；hash 未变则跳过重新摘要
- **SPEC-FILE-010e**：`findRetryable` 由 `next_retry_at` 驱动指数退避（参照 `WikiStore`）
- **SPEC-FILE-010f**：`extension` 入库前统一小写

### SPEC-FILE-011: PathFilter（排除规则）

在 FileWatcher 注册、FileIndexWorker 扫描、入队前**统一**调用。

- **SPEC-FILE-011a**：内置黑名单目录 `.git`/`node_modules`/`target`/`build`/`dist`/`.gradle`/`.idea`/`.vscode`/`out`/`bin`/`.mvn`/`__pycache__`/`venv`/`.venv`
- **SPEC-FILE-011b**：过滤隐藏文件、超 `maxFileSizeKb` 的文件
- **SPEC-FILE-011c**：默认排除敏感文件 `.env`/`.env.*`/`*.pem`/`*.key`/`id_rsa*`/`*.p12`/`*.keystore`
- **SPEC-FILE-011d**：`excludeDirs`/`excludeGlobs` 配置可追加排除
- **SPEC-FILE-011e**：watch root 存在 `.gitignore` 时尊重其规则（推荐增强）
- **SPEC-FILE-011f**：默认排除高频易变文件 `*.log`/`*.tmp`/`*.temp`/`*.lock`/`*.swp`/`*~`（这类文件不该索引，且会触发频繁变更）

### SPEC-FILE-012: FileWatcher

- 启动递归注册所有子目录到 `WatchService`（注册时跳过排除目录）
- `ENTRY_CREATE`(目录) → 经 PathFilter 递归注册新目录
- `ENTRY_CREATE`/`ENTRY_MODIFY`(文件) → PathFilter → `upsertPending()` → 发 heartbeat
- `ENTRY_DELETE` → `markDeleted()`
- 单独 daemon 线程
- **SPEC-FILE-012a**：bucket `aw-watcher-file_{hostname}`，启动 `ensureBucket()`（HTTP）
- **SPEC-FILE-012b**：heartbeat data = `{path, relative_path, watch_root, event_type, extension, size_bytes}`，pulsetime 30s
- **SPEC-FILE-012c**：macOS 无原生 FSEvents 后端，回退 `PollingWatchService`（延迟高）—— 文档需注明

### SPEC-FILE-013: FileIndexWorker

- 启动对账扫描（`Files.walkFileTree` + 廉价预筛，见 SPEC-FILE-010d）
- `ScheduledExecutorService` 周期 `file.watch.worker.intervalSeconds`，每轮取一条 PENDING
- 处理：`extract → truncate（按 codepoint）→ summarize → updateIndexed → enqueueEmbedding`
- **SPEC-FILE-013a**：失败按指数退避写 `next_retry_at`（与 `WikiWorker` 一致）
- **SPEC-FILE-013b**：单个文件提取失败不拖垮 worker，走 `MetadataOnlyExtractor` 兜底
- **SPEC-FILE-013c**：失败日志不得输出异常 message；`last_error` 只能保存固定错误码和异常类型，
  防止 LLM、解析器或提取器把 prompt/文件正文回显到持久化介质

### SPEC-FILE-019: 变更去抖与频率控制

防止频繁变化的文件（日志、编辑器自动保存、构建产物）造成 heartbeat 洪泛、读到写一半的文件、以及被无限重复摘要而流失 LLM 成本。

- **SPEC-FILE-019a（静默期 debounce，核心）**：FileWatcher 检测到变更后**不立即** `upsertPending`，而是记录 `pending_since`；仅当该文件**连续静默 ≥ `file.watch.debounceSeconds`（默认 5s）** 才真正入队。持续变化的文件因永远静默不下来而被自然推迟，同时保证读取时写入已完成
- **SPEC-FILE-019b（每文件最小重摘间隔）**：worker 处理时，若该 path 距 `last_indexed_at` 不足 `file.watch.minReindexIntervalMinutes`（默认 5min），跳过本轮，硬性封顶单文件 LLM 频率
- **SPEC-FILE-019c（heartbeat 节流）**：watcher 对同一 path 的 heartbeat 发送设最小间隔 `file.watch.heartbeatThrottleSeconds`（默认 5s），避免 HTTP 洪泛（与 AW 侧 30s pulsetime 互补：pulsetime 合并时间轴事件，节流减少请求数）
- **SPEC-FILE-019d**：高频易变文件类型由 PathFilter 直接排除（见 SPEC-FILE-011f），与上述去抖互补

### SPEC-FILE-014: FileContentExtractor 体系

接口 `extract(Path) → String`；`FileContentExtractorFactory` 按小写扩展名选实现。

| 实现 | 覆盖 | 库 |
|------|------|-----|
| `PlainTextExtractor` | .txt/.md/.csv/.json/.xml/.yaml… | JDK |
| `SourceCodeExtractor` | .java/.py/.js/.ts/.go/.rs/.kt… | JDK |
| `PdfExtractor` | .pdf | Apache PDFBox |
| `OfficeExtractor` | .docx/.xlsx/.pptx | Apache POI |
| `MetadataOnlyExtractor` | 二进制兜底 | — |

- **SPEC-FILE-014a**：`OfficeExtractor` 仅现代 OOXML，用 `org.apache.poi.extractor.ExtractorFactory.createExtractor(File).getText()` 统一识别三格式，try-with-resources 关闭（POI 5.x 中 `ExtractorFactory` 位于 `org.apache.poi.extractor` 包，由 `poi-ooxml` 提供）
- **SPEC-FILE-014b**：不支持旧二进制 .doc/.xls/.ppt
- **SPEC-FILE-014c**：合法大文件被 POI zip-bomb 检测误伤时，可在 `OfficeExtractor` 静态初始化调 `ZipSecureFile.setMinInflateRatio(...)` 放宽
- **SPEC-FILE-014d**：文件索引不执行图片 OCR；.png/.jpg 等图片与其他二进制文件统一落到 `MetadataOnlyExtractor`

### SPEC-FILE-018: OfficeExtractor 详细规格

#### 格式范围

- **SPEC-FILE-018a**：支持现代 OOXML —— `.docx`（Word）、`.xlsx`（Excel）、`.pptx`（PowerPoint）
- **SPEC-FILE-018b**：**不**支持旧二进制 `.doc/.xls/.ppt`（POI 对 .doc 提取质量差且需额外 `poi-scratchpad`）；这些扩展名落到 `MetadataOnlyExtractor` 兜底，不报错
- **SPEC-FILE-018c**：库选型为 Apache POI（`poi-ooxml`），不引入 Apache Tika

#### 提取实现

- **SPEC-FILE-018d**：统一入口 `org.apache.poi.extractor.ExtractorFactory.createExtractor(File)`（POI 5.x 包路径），按文件内容（非仅扩展名）自动识别并返回对应 `POITextExtractor`，调用 `getText()` 得纯文本
- **SPEC-FILE-018e**：`POITextExtractor` 用 try-with-resources 关闭，释放底层 `OPCPackage` 与文件句柄；`extract()` 异常向上抛由 FileIndexWorker 按 SPEC-FILE-013a/013b 处理
- **SPEC-FILE-018f**：返回的原始文本不在 extractor 内截断；截断由上层按 `maxContentChars`（codepoint）统一处理

#### 各格式提取行为

- **SPEC-FILE-018g（Word .docx）**：`XWPFWordExtractor` 输出段落正文与表格文本；页眉/页脚等非正文内容是否纳入以 POI 默认行为为准，不额外定制
- **SPEC-FILE-018h（Excel .xlsx）**：`XSSFExcelExtractor` 按 sheet → row → cell 顺序输出单元格文本（含公式计算值的文本形式）；多 sheet 全部纳入；超大表的体量由 `maxContentChars` 截断兜底，不另设行数上限
- **SPEC-FILE-018i（PowerPoint .pptx）**：`XSLFPowerPointExtractor` 输出各幻灯片的形状文本；备注（notes）是否纳入以 POI 默认为准

#### 健壮性

- **SPEC-FILE-018j**：解压后体量可能远大于压缩体积；`maxFileSizeKb` 按**磁盘字节**在 PathFilter 阶段拦截（SPEC-FILE-011b），提取阶段不再二次判断大小
- **SPEC-FILE-018k**：损坏 / 加密 / 非预期格式的文件触发 POI 异常时，FileIndexWorker 捕获并走退避重试，最终落 `MetadataOnlyExtractor` 兜底，不终止 worker
- **SPEC-FILE-018l**：zip-bomb 误伤的放宽策略见 SPEC-FILE-014c

### SPEC-FILE-015: FileSummarizer

LLM prompt（中文）输入文件路径/类型/最后修改时间/截取内容，要求输出：
1. 一段简洁摘要（≤100 字）
2. 3-5 个主题关键词
3. 文件用途推断

返回严格 JSON：`{"summary":"...","mainTopics":["..."],"estimatedPurpose":"..."}`。

- **SPEC-FILE-015a**：`prompt_version` 随 prompt 结构变化递增，便于后续按版本重摘

### SPEC-FILE-016: FileSemanticIndex + FileEmbeddingWorker

- Embedding 输入只能由相对路径、摘要和主题组成。
- Lucene 文档不得包含原始文件正文或完整摘要 prompt。

- Lucene `FSDirectory`：`{memory.dir}/file-semantic-index/`
- 字段：`path`(StringField)、`extension`(StringField)、`last_modified_ms`(LongPoint 范围)、`summary`(TextField stored)、`main_topics`(TextField stored)、`embedding`(KnnFloatVectorField cosine)
- **SPEC-FILE-016a**：一个 `FSDirectory` 同时只允许一个 `IndexWriter`；`FileEmbeddingWorker` 是唯一写入者
- **SPEC-FILE-016b**：`semantic.enabled=false` 或 embedding client 为 null 时跳过索引

### SPEC-FILE-017: FileTools（@Tool）

| 方法 | 描述 |
|------|------|
| `searchFiles(query, watchRoot, extension, start, end, topK)` | 语义向量搜索 + 目录/扩展名/时间过滤 |
| `listRecentFiles(watchRoot, start, end, limit)` | 按 last_modified 列出（无需 embedding） |
| `getFileSummary(path)` | 查指定路径摘要 |
| `fileIndexStatus()` | 各 watchRoot 的 PENDING/INDEXED/FAILED 计数 |

- **SPEC-FILE-017a**：返回 JSON 字符串，格式与 `WikiTools` 一致
- **SPEC-FILE-017b**：embedding 不可用时 `searchFiles` 降级到关键词/时间查询，不抛异常

### SPEC-FILE-020：桌面端可见性与状态 API

- `GET /desktop/status` 的 `collectors.file` 返回粗粒度状态：
  `disabled`、`running` 或 `degraded`，供顶部状态栏持续展示。
- `GET /desktop/files?limit=20` 返回文件采集概览，包含：
  `enabled`、`status`、可选 `reason/error`、`semantic`、`roots`、
  `totals`、`files`、`latestIndexedAt` 与 `latestPath`。
- `roots[].counts` 和 `totals` 使用小写状态键：
  `pending/indexed/failed/skipped/deleted`。
- `files` 只返回当前配置监控目录内最近完成索引的记录，按
  `last_indexed_at` 倒序；不得返回原始文件正文或完整提示词。
- 启动失败原因使用稳定代码：`paths_unavailable`、
  `initialization_failed`、`agent_unavailable`、`worker_start_failed`；
  存储查询失败使用 `store_unavailable`。异常详情必须压成单行且最长 200 字符。

#### SPEC-FILE-020a：桌面界面

- 顶部状态栏必须有独立的“文件”状态入口，点击进入“文件”页签。
- “文件”页签始终可见；关闭状态不得隐藏入口，而应展示功能说明、隐私提示和配置操作。
- 运行状态展示监控目录、已索引/待处理/失败计数、最近完成索引的文件摘要和主题。
- 降级状态展示本地化原因和可用的技术详情，并提供进入配置和重新加载的操作。
- 从文件页进入配置时，编辑器应定位到 `file.watch.enabled`。
- 界面必须明确说明：文件正文会发送给已配置的 LLM 生成摘要；敏感文件、构建目录和临时文件默认排除。

---

## 4. 配置

```properties
# File Watch（被监控目录的文件内容会发送给 LLM，注意隐私）
file.watch.enabled=false
file.watch.paths=                 # 逗号分隔绝对路径，可多目录
file.watch.maxFileSizeKb=512
file.watch.maxContentChars=8000   # 按 codepoint 截断
file.watch.worker.intervalSeconds=60
file.watch.debounceSeconds=5              # 文件静默≥此值才入队（去抖）
file.watch.minReindexIntervalMinutes=5   # 同一文件两次摘要的最小间隔
file.watch.heartbeatThrottleSeconds=5    # 同一文件 heartbeat 最小发送间隔
file.watch.extensions=            # 空=不限制扩展名；不支持正文提取的文件仅记录元数据
file.watch.excludeDirs=
file.watch.excludeGlobs=
file.watch.semantic.enabled=true
```

AppSession 在 `file.watch.enabled=true` 时按序初始化并启动
FileWatchStore → PathFilter → FileSemanticIndex → FileEmbeddingWorker →
FileSummarizer → FileIndexWorker → FileWatcher → FileTools；`SelfAnalystAgent`
构造器接收可空 `FileTools` 并条件注册。配置统一经 `Config` 读取（单一来源）。

所有 `file.watch.*` 键在启动阶段读取，桌面配置 API 修改这些键时必须返回
`restartRequired`；界面提示用户重启 SelfAnalyst 后生效。

---

## 5. 测试规格

| 测试 | 预期 |
|------|------|
| `FileContentExtractorFactory` | .docx/.xlsx/.pptx → `OfficeExtractor`；旧版 Office 与图片 → `MetadataOnlyExtractor` |
| `OfficeExtractor` .docx | POI 程序化生成含已知段落+表格的 .docx，提取出对应文字 |
| `OfficeExtractor` .xlsx | 生成含已知单元格（多 sheet）的 .xlsx，提取出对应文字 |
| `OfficeExtractor` .pptx | 生成含已知幻灯片文本的 .pptx，提取出对应文字 |
| `OfficeExtractor` 损坏文件 | 非法 OOXML 抛异常，由上层兜底，不崩 worker |
| `FileWatchStore` upsert / hash 未变 | 重复 upsert 跳过、status 正确流转 |
| `FileWatchStore` markDeleted / findRetryable | 删除标记、退避查询正确 |
| `PathFilter` | 黑名单目录 / 敏感文件 / 超大文件 / 易变文件(*.log…) 被排除 |
| 去抖（debounce） | 静默期内持续变更不入队；静默后只入队一次 |
| 最小重摘间隔 | 间隔内重复变更被 worker 跳过，不重复摘要 |
| `searchFiles` 无 embedding | 降级不抛异常 |
| `DesktopFileController` | disabled/running/degraded、目录计数、最近文件和错误清理正确 |
| 桌面端文件页 | 入口常驻；关闭、运行、降级三种状态均可理解并可操作 |
| 文件配置保存 | 修改任一 `file.watch.*` 键返回 `restartRequired` |

---

## 追溯矩阵

| 规格 ID | 文件 |
|---------|------|
| SPEC-FILE-001..003 | pom.xml（根 + self-analyst-file）、AppSession.java |
| SPEC-FILE-004 | FileWatcher.java + FileIndexWorker.java + FileWatchStore.java |
| SPEC-FILE-010 | FileWatchStore.java |
| SPEC-FILE-011 | PathFilter.java |
| SPEC-FILE-012 | FileWatcher.java |
| SPEC-FILE-013 | FileIndexWorker.java |
| SPEC-FILE-019 | FileWatcher.java（debounce/节流）+ FileIndexWorker.java（重摘间隔） |
| SPEC-FILE-014 | extractor/*.java + FileContentExtractorFactory.java |
| SPEC-FILE-018 | extractor/OfficeExtractor.java + pom.xml（poi-ooxml） |
| SPEC-FILE-015 | FileSummarizer.java |
| SPEC-FILE-016 | semantic/FileSemanticIndex.java + FileEmbeddingWorker.java |
| SPEC-FILE-017 | FileTools.java + agent/SelfAnalystAgent.java |
