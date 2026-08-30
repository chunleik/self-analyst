# 文件元数据采集规范

> 本规范定义严格隐私边界下的本地文件采集、持久化、查询与桌面端展示行为。

## 1. 目标与边界

文件采集器用于回答“有哪些文件”“文件在哪里”“何时创建或修改”“最近哪些文件发生变化”等问题。
它不是文件内容索引器，不得理解、摘要或向量化文件正文。

- **SPEC-FILE-001（严格元数据边界）**：文件模块只能采集文件名、绝对路径、相对路径、监控根目录、
  扩展名、字节大小、文件系统创建时间、最后修改时间，以及采集流程所需的状态、重试时间和错误类型。
- **SPEC-FILE-002（禁止读取正文）**：文件模块不得为采集目的打开普通被监控文件的字节流，不得调用
  文本/PDF/Office 提取器或等价正文读取入口。唯一例外是读取监控树内 `.gitignore` 过滤规则；
  规则只在内存解析，不得持久化或外发。
- **SPEC-FILE-003（禁止正文派生）**：不得计算内容哈希，不得生成或保存正文摘要、主题、用途、
  prompt、模型名称或由正文产生的 embedding。
- **SPEC-FILE-004（禁止外发）**：文件正文不得发送给 LLM、embedding、MCP、搜索服务或其他进程；
  文件元数据查询必须在本地完成。
- **SPEC-FILE-005（标题语义）**：文件名及其相对路径是文件模块唯一的“标题”事实。系统不得把正文首行、
  摘要或主题冒充文件标题。

上述边界同时适用于成功、失败、重试、日志、ActivityWatch 事件、SQLite、WAL/SHM、备份、导出和桌面 API。

## 2. 数据流

```text
监控根目录
  ├─ FileIndexWorker 首次对账扫描
  │    └─ Files.readAttributes(BasicFileAttributes)
  └─ FileWatcher / WatchService
       └─ CREATE / MODIFY 静默去抖

路径过滤
  → 扩展名/目录/glob/.gitignore/链接边界
  → FileWatchStore.upsertPending()
  → FileIndexWorker 每轮批量读取 BasicFileAttributes
  → FileWatchStore.updateCollected()
  → 本地路径/时间查询与元数据 heartbeat
```

链路中不存在正文提取、内容哈希、摘要器或文件语义索引。

## 3. 生命周期与状态

- **SPEC-FILE-010**：状态为 `PENDING | COLLECTED | FAILED | SKIPPED | DELETED`。
- **SPEC-FILE-011**：新文件或元数据变化进入 `PENDING`；成功读取属性后进入 `COLLECTED`。
- **SPEC-FILE-012**：文件不存在时进入 `DELETED`；重新出现时可再次进入 `PENDING`。
- **SPEC-FILE-013**：属性读取失败时只保存固定错误码 `FILE_METADATA_FAILED:<ExceptionType>`，
  按指数退避重试；不得保存异常 message。
- **SPEC-FILE-014**：每轮最多批量处理 256 条，避免元数据采集仍按旧 LLM 节奏逐文件积压。

`last_collected_at` 表示最近一次成功采集元数据的时间；它不同于文件系统提供的
`file_created_at` 和 `last_modified`。

## 4. PathFilter

FileWatcher 注册、首次扫描和入队前统一调用 PathFilter。

- **SPEC-FILE-020**：内置排除目录名不区分大小写，包括 `.git`、`node_modules`、`target`、`build`、
  `dist`、`.gradle`、`.idea`、`.vscode`、`out`、`bin`、`obj`、`.mvn`、`__pycache__`、`venv`、
  `.venv`、`coverage`、`bower_components`、`vendor`、`Pods`。所有点前缀目录整棵跳过。
- **SPEC-FILE-021**：排除隐藏文件/目录、超过 `maxFileSizeKb` 的文件、用户目录名与相对 watch root
  的 glob。目录 glob 命中时必须在遍历和 WatchService 注册前 `SKIP_SUBTREE`；无效配置拒绝保存/启动。
- **SPEC-FILE-022**：默认排除敏感文件 `.env`、`.env.*`、`*.pem`、`*.key`、`id_rsa*`、
  `*.p12`、`*.keystore`。
- **SPEC-FILE-023**：默认排除高频易变文件 `*.log`、`*.tmp`、`*.temp`、`*.lock`、`*.swp`、
  `*~`、`~$*`、`*.autosave`、`*.bak`。
- **SPEC-FILE-024**：默认白名单只含 Office/Markdown 后缀；空列表不采集任何文件，只有显式 `*`
  才允许全部后缀。后缀不区分大小写，允许一个前导点，非法 token 必须拒绝。不支持的后缀应仅凭
  已有属性与文件名尽早拒绝，不执行祖先链和 `.gitignore` 的昂贵检查。
- **SPEC-FILE-025**：`respectGitIgnore=true` 时支持根与嵌套 `.gitignore`、目录规则、通配符、锚定
  与 `!` 否定；被上层规则排除的目录不能由其内部规则重新包含后代。使用 JGit ignore matcher，
  不读取全局 excludes 或 `.git/info/exclude`；缓存必须绑定目录身份，规则文件超过 1 MiB、不是普通文件、
  路径链含链接或无法通过 `NOFOLLOW_LINKS` 及目录链/规则文件读取前后身份校验时，该路径树暂时
  fail-closed。规则原文只存在于
  解析期间的内存，不进入缓存、日志、错误信息或任何持久化/外发数据。
- **SPEC-FILE-026**：扫描与目录注册显式不跟随 symlink；符号链接、`isOther`、junction/reparse point、
  根目录外路径和无法证明安全的特殊节点 fail-closed；文件判定还必须逐级验证从 watch root 到父目录
  的每个节点，不能只检查最终文件。

## 5. FileWatcher

- **SPEC-FILE-030**：启动时递归注册非排除子目录到 JDK `WatchService`。
- **SPEC-FILE-031**：文件 CREATE/MODIFY 经静默期去抖后只写入元数据采集意图。
- **SPEC-FILE-032**：DELETE 只更新已知路径状态，不读取文件。
- **SPEC-FILE-032a**：目录 DELETE 或监控根目录的 WatchKey 因目录消失而失效时，必须把已采集的
  后代路径一并标记为 `DELETED`；无法确认目录不存在时不得淘汰。
- **SPEC-FILE-033**：同一路径 heartbeat 按配置节流。
- **SPEC-FILE-034**：heartbeat data 白名单为：
  `path`、`relative_path`、`watch_root`、`event_type`、`extension`、`size_bytes`、
  `file_created_at`、`last_modified`。不得出现正文、哈希、摘要、主题或向量。
- **SPEC-FILE-035**：`.gitignore` CREATE/MODIFY/DELETE 只触发规则缓存失效、目录注册修复与对应子树对账，
  不作为普通文件入队或发送 heartbeat；WatchService `OVERFLOW` 必须同时失效对应规则缓存、修复目录注册
  并触发安全子树对账。新建的已填充目录在注册后也必须请求子树对账，补回注册前已存在的文件。

## 6. FileIndexWorker

历史类名 `FileIndexWorker` 为兼容保留，实际职责是文件系统元数据采集。

- **SPEC-FILE-040**：首次扫描只遍历路径并读取 `BasicFileAttributes`。
- **SPEC-FILE-041**：变更判定仅比较大小、文件创建时间、最后修改时间和路径归属。
- **SPEC-FILE-042**：处理 PENDING/FAILED 时只调用 `Files.isRegularFile` 与
  `Files.readAttributes(..., BasicFileAttributes.class)`。
- **SPEC-FILE-043**：后台线程不依赖 Agent、LLM、embedding 配置或 API key；即使 Agent 不可用，
  文件元数据采集仍可启动。
- **SPEC-FILE-044**：完整且无访问错误的首次扫描结束后，必须把该根目录下未再出现的历史记录标记为
  `DELETED`；扫描发生访问错误或被取消时不得执行缺失记录淘汰。扫描期间由 watcher 新增/更新的行
  必须通过 `updated_at` 快照和文件存在性复核排除，避免并发 CREATE 被误删。
- **SPEC-FILE-045**：worker 接受按 root 合并的有界子树对账请求；请求必须位于 active root 内，
  使用与首次扫描相同的 PathFilter，并使新排除记录进入 `DELETED`、重新包含记录进入 `PENDING`。

## 7. SQLite v2

数据库路径保持 `{memory.dir}/file-watch.db`，schema version 为 2，主表为 `file_metadata`：

```sql
CREATE TABLE file_metadata (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  absolute_path TEXT NOT NULL UNIQUE,
  relative_path TEXT,
  watch_root TEXT,
  extension TEXT,
  size_bytes INTEGER NOT NULL DEFAULT 0,
  file_created_at TEXT,
  last_modified TEXT,
  first_seen_at TEXT NOT NULL,
  last_collected_at TEXT,
  status TEXT NOT NULL,
  retry_count INTEGER NOT NULL DEFAULT 0,
  next_retry_at TEXT,
  last_error TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);
```

- **SPEC-FILE-050（不可表达正文）**：schema 和 `FileRecord` 类型均不得包含 `content`、`file_hash`、
  `summary`、`main_topics`、`model`、`prompt`、`embedding` 或语义等价字段。
- **SPEC-FILE-051（v1 净化迁移）**：打开 v1 或未标版本的旧数据库时必须只复制允许的元数据到 v2 表，
  将 `INDEXED` 映射为 `COLLECTED`，以 `secure_delete=ON` 删除旧表并执行 `VACUUM`；只有 `VACUUM`
  成功后才能写入 schema version 2。若进程在两阶段之间中断，下次启动必须识别
  `file_metadata` 已存在而 `file_index` 已删除的中间态并继续净化。
- **SPEC-FILE-052**：启用 WAL 和 `busy_timeout=5000`；跨线程访问继续由 store 实例串行化。
- **SPEC-FILE-053**：旧 Lucene 文件内容索引在启动时清理；删除前必须完整验证目录仅含 Lucene
  artifact、文件名符合精确 grammar，且存在带 Lucene codec magic 的已提交 `segments_[base36]`。
  `pending_segments_*` 不能作为提交证明。根目录、符号链接、混合目录和无法验证的目录必须在删除
  任何文件前整体拒绝；不得递归删除任意目录。数据 artifact 必须先删、commit point 最后删，
  使中途失败后仍能在下次启动识别并重试。
- **SPEC-FILE-054**：旧 `file.watch.semantic.*`、`maxContentChars`、
  `minReindexIntervalMinutes` 配置不再受支持；旧 semantic index 路径仅在迁移期读取以完成净化。

## 8. FileTools

FileTools 仅提供本地元数据工具：

| 工具 | 行为 |
|---|---|
| `searchFiles` | 按文件名/相对路径关键词、目录、扩展名、修改时间查询 |
| `listRecentFiles` | 按最后修改时间列出文件元数据 |
| `getFileMetadata` | 返回指定路径的文件名、路径、大小、创建/修改/采集时间 |
| `fileCollectionStatus` | 返回各监控根目录状态计数 |

- **SPEC-FILE-060**：所有工具响应不得包含摘要、主题、内容哈希、模型或 prompt。
- **SPEC-FILE-061**：`searchFiles` 使用 SQLite 路径匹配，不得调用 embedding 服务。
- **SPEC-FILE-062**：用户询问“文件写了什么”时，Agent 必须说明 FileTools 无法读取正文，只能提供元数据。
- **SPEC-FILE-063**：所有 FileTools 查询必须限制在当前已启用的监控根目录；禁用采集或移除目录后，
  该目录的历史名称、路径和时间不得继续暴露给 Agent。

## 9. Desktop API 与界面

`GET /desktop/files` 返回：

- `enabled/status/reason/error/restartRequiredOnChange`
- `roots[].path/counts`
- `totals`
- `files[]`：`name/path/relativePath/watchRoot/extension/status/sizeBytes/fileCreatedAt/lastModified/lastCollectedAt`
- `latestCollectedAt/latestPath`

- **SPEC-FILE-070**：响应不得再提供 `semantic`、`summary`、`mainTopics` 或 `lastIndexedAt`。
- **SPEC-FILE-071**：界面统一使用“已采集/元数据”，不得使用“内容索引/摘要/语义检索”。
- **SPEC-FILE-072**：隐私提示必须明确声明“不读取正文、不计算内容哈希、不发送给 LLM/embedding”。
- **SPEC-FILE-073**：`PUT /desktop/files/settings` 继续支持监控目录与 enabled 热更新。

## 10. 配置

```properties
file.watch.enabled=false
file.watch.paths=
file.watch.maxFileSizeKb=0
file.watch.worker.intervalSeconds=60
file.watch.debounceSeconds=5
file.watch.heartbeatThrottleSeconds=5
file.watch.extensions=doc,docx,docm,xls,xlsx,xlsm,xlsb,ppt,pptx,pptm,pps,ppsx,ppsm,pot,potx,potm,md,markdown
file.watch.excludeDirs=
file.watch.excludeGlobs=
file.watch.respectGitIgnore=true
```

## 11. 验收与回归

- **SPEC-FILE-TST-001**：处理带唯一秘密标记的文件后，`file-watch.db`、WAL、SHM 均不含该标记。
- **SPEC-FILE-TST-002**：`FileIndexWorker` 字节码不得引用内容流、哈希、摘要器、提取器或文件 embedding 类。
- **SPEC-FILE-TST-003**：v1 迁移后 schema 不含禁止字段，旧摘要/主题标记从数据库字节中消失。
- **SPEC-FILE-TST-004**：创建时间、修改时间、大小和路径可以正确采集与查询。
- **SPEC-FILE-TST-005**：FileTools 与 Desktop API 响应不含摘要、主题、哈希、模型或 prompt。
- **SPEC-FILE-TST-006**：旧 Lucene 索引文件被清理，文件系统根目录受到保护。
- **SPEC-FILE-TST-007**：文件采集在 Agent/LLM 不可用时仍可运行。
- **SPEC-FILE-TST-008**：桌面 UI 只展示元数据与严格隐私提示。
- **SPEC-FILE-TST-009**：混有普通文件的自定义旧索引目录在删除任何文件前被拒绝。
- **SPEC-FILE-TST-010**：`VACUUM` 前中断后，下次启动继续净化并最终写入 schema version 2。
- **SPEC-FILE-TST-011**：移除/禁用监控根目录后，所有 Agent 文件工具都隐藏对应历史元数据。
- **SPEC-FILE-TST-012**：停机期间删除文件或运行期间删除子目录后，历史记录进入 `DELETED`。
- **SPEC-FILE-TST-013**：扫描快照后并发创建并入队的文件不会被缺失记录对账误删。
- **SPEC-FILE-TST-014**：删除监控根目录导致 WatchKey 失效时，其后代历史记录进入 `DELETED`。
- **SPEC-FILE-TST-015**：伪造前缀/无效 codec header 不能通过旧索引验证；删除中断时 commit point
  保留且下次清理可恢复。
- **SPEC-FILE-TST-016**：空后缀列表 fail-closed，`*` 显式放行，默认仅允许 Office/Markdown。
- **SPEC-FILE-TST-017**：目录名大小写、点前缀工具目录、相对路径 glob 与 Office 临时文件均被早期排除。
- **SPEC-FILE-TST-018**：根与嵌套 `.gitignore`、否定规则、运行时规则变更和 `OVERFLOW` 对账可用。
- **SPEC-FILE-TST-019**：symlink、junction/`isOther` 和 root 外路径不能注册或采集。
- **SPEC-FILE-TST-020**：`.gitignore` 无法安全判定或扫描发生访问错误时不得把既有元数据误标为
  `DELETED`；错误恢复后可通过规则事件或后续对账收敛。
