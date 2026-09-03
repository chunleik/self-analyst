# 原始采集事件永久保留设计方案

> 状态：设计草案，尚未实现
>
> 日期：2026-08-30
>
> 适用模块：`self-analyst-aw`、`self-analyst-content`、`self-analyst-file`、
> `self-analyst-wiki`、`self-analyst-app`

## 1. 设计结论

SelfAnalyst 将采用“合规原始事件永久保留、派生数据可删除并重建”的数据架构：

```text
采集器
  │
  ├─ 采集端隐私最小化与类型校验
  │
  ▼
不可变原始事件层（永久保留、只追加）
  │
  ├─ ActivityWatch 兼容时间线投影
  ├─ 小时/半天/天统计
  ├─ Wiki 摘要与任务片段
  └─ 语义索引
       ▲
       └─ 均可由原始事件重新生成
```

应用不得因为磁盘占用、事件年龄、配置变化或派生成功而自动删除合规原始事件。磁盘压力只能触发
告警、背压或暂停新采集，不能触发自动淘汰。

本设计中的“永久保留”表示应用不主动删除已经提交的合规原始事件，不代表能够抵御磁盘损坏、用户
手工删除、操作系统故障或未配置备份造成的数据丢失。

## 2. 背景

当前项目把采集结果直接写入 ActivityWatch 兼容的 `events` 表。heartbeat 写入时，如果最后一条事件的
JSON 数据相同且时间间隔在 `pulsetime` 内，`EventStore` 会直接更新上一行的 `duration`。这种方式适合
生成紧凑时间线，但不能保留每次 heartbeat 的原始到达记录。

当前 Wiki、桌面时间线和 Agent 查询也直接依赖同一事件表。一旦未来调整 heartbeat 合并、标题规则、
统计口径或 Wiki 算法，无法从完整的原始采集事件重新生成结果。此外，单个持续增长的 `aw.db` 会增加
备份、校验和长期查询成本。

因此需要在采集入口与现有 ActivityWatch 投影之间增加不可变原始事件层，把“事实保存”和“展示/分析”
解耦。

## 3. 术语与数据边界

### 3.1 合规原始事件

合规原始事件是指：采集器提交到本地服务端、经过来源 schema 和隐私策略校验、但尚未进行 heartbeat
合并、时间聚合、摘要或 embedding 的事件。

按来源划分：

| 来源 | 合规原始事件允许包含 |
|------|----------------------|
| 窗口 | 应用名、系统窗口标题、事件时间、持续时间 |
| AFK | `afk/not-afk` 状态、事件时间、持续时间 |
| 内容标题 | 内容事件 v2 白名单字段、事件时间、持续时间 |
| 文件 | 启用监控根目录内的路径元数据、大小、创建/修改时间、事件类型 |
| 导入 | 通过对应 bucket schema 和内容事件策略校验后的事件 |

原始层保存的是事件的规范化语义值，不保存 HTTP header、认证信息、请求空白、无效字段或整个原始
HTTP body 字节串。

### 3.2 不属于可持久化原始数据的内容

下列数据仍然不得进入原始事件层：

- 完整 UIA 树、UIA 拼接文本、控件正文或控件值；
- `text_content`、`uia_text`、`raw_tree`、正文首句或正文截断；
- 屏幕截图、OCR 原文、OCR 样本；
- 麦克风、系统声音和语音转写；
- 普通监控文件的正文、内容哈希、正文摘要、主题或文件 embedding；
- API key、Authorization header、桌面 token 或完整 LLM prompt。

“原始事件永久保留”不得被解释为重新启用已移除的正文、OCR 或声音采集。

### 3.3 派生数据

派生数据包括：

- ActivityWatch 合并时间线；
- 应用时长、AFK 时长、切换数等聚合指标；
- Wiki 摘要、主要任务和任务片段；
- Lucene 文档和 embedding 向量；
- 桌面缓存、查询缓存和重建游标。

派生数据可以清理、升级或重建，但任何清理操作不得影响原始事件层。

## 4. 目标

- 永久保留每个成功接收的合规原始事件。
- 原始事件提交后只追加，不允许原地修改或删除。
- heartbeat 合并只发生在 ActivityWatch 投影层。
- 任何派生数据库损坏后都能从原始事件重建。
- 使用时间分区控制长期数据规模和单库维护成本。
- 保持现有 ActivityWatch API、桌面时间线、Wiki 和 Agent 的主要查询行为。
- 保持现有内容标题最小化和文件 metadata-only 隐私边界。
- 对写入失败、投影失败、磁盘不足和进程崩溃提供可恢复语义。

## 5. 非目标

- 不保证在磁盘损坏或用户手工删除后的绝对不可丢失性。
- 第一阶段不提供云同步或远程原始数据仓库。
- 不把 Agent 默认查询切换到逐条原始 heartbeat。
- 不在原始层执行正文搜索、全文索引或语义检索。
- 不恢复历史版本中已经移除的 UIA 正文、OCR、截图或声音能力。
- 不设计现有开发数据的导入、兼容或精度标记；永久保留契约从新原始事件层启用时开始。

## 6. 核心原则

### 6.1 原始数据先提交

所有新事件必须先成功写入原始事件层，之后才能更新 ActivityWatch 投影。原始写入失败时不得仅写入
投影，否则会形成无法重建的事实缺口。

### 6.2 原始数据不可变

原始事件表不提供 `UPDATE` 或 `DELETE` 业务入口。纠错通过追加更正事件或 schema 新版本完成，不修改
旧事件。

### 6.3 投影可重放

ActivityWatch `events` 表、Wiki 和语义索引都必须具有明确的投影版本与重建入口。算法变更时创建新
投影或重放原始事件，不修改原始记录。

### 6.4 隐私校验先于永久保存

永久保留放大了错误采集的风险，因此内容事件白名单、文件 metadata-only 边界、请求大小限制和字段
长度限制必须在原始提交之前执行。

### 6.5 不以自动删除解决容量问题

容量问题通过分区、压缩、索引分层、增量查询、外部备份和用户扩容解决。应用不得以 FIFO、TTL 或
“保留最近 N 天”的方式自动删除原始事件。

## 7. 总体架构

```text
WindowWatcher / AfkWatcher / ContentWatcher / FileWatcher / Import
                              │
                              ▼
                     IngestionController
               schema 校验、隐私校验、大小限制
                              │
                              ▼
                      RawEventStore
             当前月份可写分区，历史月份只读分区
                              │
                   原始提交成功后触发投影
                              ▼
                       EventProjector
               heartbeat 合并、字段派生、幂等写入
                              │
                              ▼
                         aw.db
                ┌─────────────┼─────────────┐
                ▼             ▼             ▼
              AQL          WikiWorker    Desktop/Agent
                               │
                               ▼
                      llm-wiki.db / Lucene
```

### 7.1 组件职责

| 组件 | 职责 |
|------|------|
| `IngestionController` | 解析请求、校验来源 schema、应用隐私策略、生成接收元数据 |
| `RawEventStore` | 永久、只追加地保存合规原始事件 |
| `RawPartitionCatalog` | 维护分区时间范围、路径、状态、数量、校验和与 schema 版本 |
| `EventProjector` | 从原始事件生成 ActivityWatch 兼容事件并执行 heartbeat 合并 |
| `ProjectionCheckpointStore` | 保存各原始分区的投影进度，不写入原始事件行 |
| `RawEventQueryService` | 按时间、bucket 和游标只读查询原始事件 |
| `RebuildService` | 从原始事件重建 `aw.db`、Wiki 或语义索引 |

## 8. 原始事件模型

### 8.1 事件结构

```java
record RawEvent(
    String eventId,
    String sourceEventId,
    String bucketId,
    String source,
    int schemaVersion,
    String ingestKind,
    Instant eventTimestamp,
    Instant receivedAt,
    double duration,
    String canonicalDataJson,
    String dataSha256,
    String importSessionId,
    Integer importOrdinal
) {}
```

字段说明：

- `eventId`：服务端原始事件主键，使用 UUIDv7 或等价的时间有序 ID。
- `sourceEventId`：采集器提供的稳定事件 ID；自有 watcher 必须提供。
- `bucketId`：ActivityWatch bucket ID。
- `source`：`window/afk/content/file/import` 等受控枚举。
- `schemaVersion`：来源事件 schema 版本。
- `ingestKind`：`heartbeat/events/import`。
- `eventTimestamp`：采集事件时间。
- `receivedAt`：本地服务端成功接收时间，用于分区和审计。
- `duration`：采集器提交的原始持续时间，不在原始层合并。
- `canonicalDataJson`：通过隐私校验后的规范化 JSON。
- `dataSha256`：规范化 JSON 的 SHA-256，用于完整性检查，不用于删除重复原始记录。
- `importSessionId/importOrdinal`：保证同一次导入重试可幂等。

### 8.2 SQLite schema

每个原始分区使用独立 SQLite 文件：

```sql
CREATE TABLE raw_events (
    event_id TEXT PRIMARY KEY,
    source_event_id TEXT,
    bucket_id TEXT NOT NULL,
    source TEXT NOT NULL,
    schema_version INTEGER NOT NULL,
    ingest_kind TEXT NOT NULL,
    event_timestamp TEXT NOT NULL,
    received_at TEXT NOT NULL,
    duration REAL NOT NULL,
    canonical_data_json TEXT NOT NULL,
    data_sha256 TEXT NOT NULL,
    import_session_id TEXT,
    import_ordinal INTEGER
);

CREATE UNIQUE INDEX ux_raw_source_event
    ON raw_events(bucket_id, source_event_id)
    WHERE source_event_id IS NOT NULL;

CREATE UNIQUE INDEX ux_raw_import_event
    ON raw_events(import_session_id, bucket_id, import_ordinal)
    WHERE import_session_id IS NOT NULL;

CREATE INDEX idx_raw_bucket_event_time
    ON raw_events(bucket_id, event_timestamp, event_id);

CREATE INDEX idx_raw_received
    ON raw_events(received_at, event_id);
```

原始分区中不建立针对标题或 JSON 正文的全文索引。常用查询由 bucket、时间和事件 ID 索引完成。

### 8.3 幂等语义

- 自有 watcher 为每个逻辑 heartbeat 生成稳定 `sourceEventId`，重试时复用。
- `/events` 批量请求中的每条事件携带稳定 ID。
- 导入使用 `importSessionId + bucketId + importOrdinal` 保证重试幂等。
- 第三方 ActivityWatch 客户端未提供事件 ID 时，服务端为每次请求生成新 `eventId`。由于无法可靠区分
  合法重复事件和网络重试，原始层不得仅凭 payload hash 丢弃它们。
- `dataSha256` 只用于校验和诊断；标准化投影可以根据业务规则折叠重复，但原始记录仍保留。

## 9. 写入与故障一致性

### 9.1 正常写入

```text
1. 解析请求
2. 校验 bucket 与来源 schema
3. 执行 ContentEventPolicy 或文件字段策略
4. 生成 canonicalDataJson 和校验和
5. 提交 RawEventStore 事务
6. EventProjector 幂等写入 aw.db
7. 更新投影 checkpoint
8. 返回兼容响应
```

### 9.2 原始写入失败

- 不写入 `aw.db`。
- HTTP 返回失败。
- watcher 状态变为 `degraded` 并重试同一 `sourceEventId`。
- 日志只记录事件 ID、bucket、异常类型和分区，不记录标题或完整 payload。

### 9.3 原始写入成功、投影失败

- 原始事件已经安全保留，不回滚或删除。
- 将投影状态保持为待处理。
- 后台 `EventProjector` 从 checkpoint 继续重放。
- 自有 watcher 重试相同 `sourceEventId` 时，原始写入命中幂等记录，再次尝试投影。
- HTTP 响应必须明确区分“原始已接收、投影待处理”和“完全失败”。

### 9.4 进程崩溃

原始 SQLite 事务保证单条或批量提交原子性。启动后先扫描未完成投影的原始事件，再启动 Wiki 等消费
者，避免派生数据永久遗漏。

## 10. heartbeat 与 ActivityWatch 投影

原始层每个 heartbeat 一行，不修改历史 heartbeat。现有紧凑时间线由 `EventProjector` 生成：

- 相邻事件的规范化 data 相同；
- 时间间隔位于实际生效的 `pulsetime` 内；
- bucket、schema 和来源一致；
- 时间顺序合法；

满足条件时，投影层可以延长上一条 `events.duration`。投影行必须能追溯到原始事件范围：

```sql
CREATE TABLE event_projection_sources (
    projection_event_id INTEGER NOT NULL,
    first_raw_event_id TEXT NOT NULL,
    last_raw_event_id TEXT NOT NULL,
    raw_event_count INTEGER NOT NULL,
    projector_version TEXT NOT NULL,
    PRIMARY KEY (projection_event_id)
);
```

标准 ActivityWatch API 和 AQL 默认查询投影层，以保持性能和兼容性。需要检查采集细节时，使用独立的
原始事件查询 API。

## 11. 时间分区与永久存储

### 11.1 分区方式

按 `receivedAt` 的 UTC 月份创建分区：

```text
{aw.data-dir}/raw/
├── catalog.db
├── 2026/
│   ├── raw-events-2026-08.db
│   ├── raw-events-2026-08.manifest.json
│   ├── raw-events-2026-09.db
│   └── raw-events-2026-09.manifest.json
└── quarantine/
```

只有当前月份分区可写。跨月后旧分区进入封存流程，新事件立即写入新分区。

### 11.2 分区目录

`catalog.db` 保存：

```sql
CREATE TABLE raw_partitions (
    partition_id TEXT PRIMARY KEY,
    path TEXT NOT NULL UNIQUE,
    period_start TEXT NOT NULL,
    period_end TEXT NOT NULL,
    state TEXT NOT NULL,
    event_count INTEGER NOT NULL,
    first_event_id TEXT,
    last_event_id TEXT,
    file_size_bytes INTEGER NOT NULL,
    file_sha256 TEXT,
    schema_version INTEGER NOT NULL,
    sealed_at TEXT,
    last_verified_at TEXT
);
```

`state` 为 `ACTIVE | SEALING | SEALED | QUARANTINED`。

### 11.3 封存流程

1. 停止向旧分区分配新事件。
2. 提交未完成事务并执行 WAL checkpoint。
3. 执行 `PRAGMA integrity_check`。
4. 统计事件数量、首尾 ID、时间范围和文件大小。
5. 计算数据库文件 SHA-256。
6. 原子写入 manifest。
7. 将 catalog 状态更新为 `SEALED`。
8. 以只读方式重新打开历史分区。

封存失败时状态保持 `SEALING` 或转为 `QUARANTINED`，不得删除数据库、WAL、manifest 或可恢复的
临时文件。启动后继续恢复。

### 11.4 容量处理

应用不得自动删除旧分区。磁盘空间策略为：

- 达到警告阈值：桌面状态和日志持续告警。
- 达到严重阈值：停止非必要派生重建，优先保证原始写入。
- 无法保证下一次原始事务写入：采集进入 `degraded/blocked`，拒绝新事件并提示扩容。
- 禁止为了恢复采集而自动删除最旧分区。

可以增加离线压缩归档，但压缩前后必须校验事件数量和内容 hash，catalog 必须指向仍可读取的唯一
有效副本。转存到压缩归档属于存储介质调整，不属于数据删除；旧副本只有在新副本完成验证且用户
明确允许清理冗余副本时才能移除。

## 12. 查询设计

### 12.1 投影查询

现有 `/api/0/buckets/{id}/events`、AQL、Wiki 和桌面时间线继续查询 `aw.db` 投影，避免扫描大量逐次
heartbeat。

### 12.2 原始事件查询

新增只读接口：

```text
GET /desktop/raw-events
    ?bucketId=...
    &start=...
    &end=...
    &cursor=...
    &limit=...
```

约束：

- `bucketId/start/end` 必填；
- 时间范围必须有上限；
- 使用稳定的 `receivedAt + eventId` 游标；
- 单页限制不超过配置上限；
- 受桌面 token 保护；
- 默认不向 Agent 注册逐条原始事件工具；
- 内容事件响应仍只可能包含 v2 白名单字段。

### 12.3 跨分区查询

`RawEventQueryService` 先从 catalog 找出相交分区，再按时间顺序流式查询。不得一次性把所有分区 attach
到同一个长期连接，也不得无时间范围扫描全部历史。

## 13. 派生数据重建

### 13.1 ActivityWatch 投影重建

重建步骤：

1. 创建新的 `aw.db.rebuilding-<id>`。
2. 按分区和 `eventId` 顺序重放原始事件。
3. 使用指定 `projectorVersion` 生成 bucket、events 和索引。
4. 比较原始事件覆盖范围、投影计数和统计校验。
5. 成功后原子切换当前投影数据库。
6. 旧投影是可删除派生数据，但清理必须是显式维护操作。

### 13.2 Wiki 和语义索引重建

- Wiki 条目记录 `factBuilderVersion`、`promptVersion` 和原始覆盖范围。
- 统计口径变化后，将受影响时间块标记为待重建。
- LLM Wiki 只读取 ActivityWatch 投影中的标题事实和本地聚合指标。
- embedding 只处理 Wiki 摘要与任务片段。
- `llm-wiki.db` 和 Lucene 损坏不影响原始事件完整性。

## 14. 内容与文件特殊规则

### 14.1 内容标题事件

`ContentEventPolicy.validate` 必须在 `RawEventStore.append` 之前执行。任何包含禁止字段的事件都应返回
`422`，且不得进入原始分区、投影、日志或失败队列。

新采集器不得生成 `ocr_title/ocr_chars`。

### 14.2 文件事件

文件原始事件仍受 metadata-only 规格约束。永久保留的是文件元数据变化，不是文件内容快照。文件
正文、内容哈希、摘要、主题和 embedding 不得因为引入原始层而重新出现。

后续可把 AW file bucket 简化为时间线引用，而由 `file-watch.db` 提供当前状态；但原始文件元数据事件
仍由 `RawEventStore` 永久保存。

### 14.3 开发阶段启用边界

当前项目仍处于开发阶段，本方案不设计现有 `aw.db`、旧独立 bucket 数据库或其他开发数据的迁移。
永久保留契约从 `RawEventStore` 正式启用后的第一条合规原始事件开始：

- 不扫描或导入启用前的 `aw.db` 事件；
- 不为旧 schema 增加兼容读取路径；
- 现有开发环境数据不由本方案自动修改或删除；开发环境初始化由实施过程单独处理。

## 15. 删除语义调整

引入永久原始层后：

- `DELETE bucket` 只能删除或隐藏 ActivityWatch 投影，不能删除原始事件。
- WikiSummaryWatcher 的重建只能操作 Wiki 投影 bucket。
- 文件目录被移除后，FileTools 不再暴露其历史记录，但原始事件仍保留。
- 用户清空 Wiki、语义索引、桌面缓存或时间线投影时，原始分区不受影响。
- 原始事件层不提供常规删除 API。

如果未来因法律、用户隐私请求或密钥泄漏需要不可逆删除，必须单独设计“受审计的显式销毁流程”；
该流程不属于本方案，也不能作为后台自动维护任务实现。

## 16. 配置建议

```properties
aw.raw.enabled=true
aw.raw.dir={aw.data-dir}/raw
aw.raw.partition=month
aw.raw.query.maxRangeDays=31
aw.raw.query.maxPageSize=1000
aw.raw.lowDisk.warnBytes=10737418240
aw.raw.lowDisk.blockBytes=1073741824
aw.raw.integrity.verifyOnStartup=latest
aw.raw.projector.batchSize=1000
```

约束：

- `aw.raw.enabled` 在原始事件层正式启用后应成为固定开启项，不作为普通桌面开关。
- 不提供 `retentionDays`、`maxPartitions` 或自动删除配置。
- 已产生原始分区后，修改 `raw.dir` 必须通过显式转存流程，不能只改变路径后遗忘旧分区。
- 严重磁盘阈值必须小于警告阈值。

## 17. 桌面端状态与运维

桌面状态页增加：

- 原始事件层状态：`running/degraded/blocked`；
- 当前活动分区；
- 分区数量、最早/最晚事件时间；
- 原始数据总大小和最近增长速度；
- 投影延迟和待投影事件数；
- 最近一次完整性校验结果；
- 磁盘剩余空间与告警；
- 最近一次备份状态（如果用户配置了备份）。

界面必须明确区分：

- “清理派生数据”：可重建，不影响原始事件；
- “验证原始数据”：执行只读完整性检查；
- “导出原始数据”：显式、受保护的用户操作；
- 不提供“自动清理最旧原始数据”。

## 18. 安全与备份

- 原始目录仅允许当前用户和应用进程访问。
- 本地 API 继续绑定回环地址并执行 Host、Origin 和桌面凭据校验。
- manifest 和校验和不得包含事件正文。
- 日志不得输出 `canonicalDataJson`。
- 原始导出必须要求显式用户操作并显示导出范围。
- 永久保留应配套可选备份；建议至少保存一个不同物理介质副本。
- 如果引入静态加密，密钥应由操作系统凭据设施保护；丢失密钥会导致永久数据不可恢复，必须在产品
  文档中明确说明。

## 19. 可观测性

增加不包含用户内容的指标：

- 原始事件写入成功、失败和幂等命中数；
- 每个来源的事件速率和平均事件字节数；
- 当前分区大小、WAL 大小和事务耗时；
- 投影 checkpoint、延迟和重试数；
- 分区封存、校验和恢复结果；
- 磁盘告警次数和被拒绝的新事件数；
- 重建覆盖事件数和耗时。

指标标签不得包含窗口标题、文件路径、上下文标题或事件 payload。

## 20. 实施方案

### 阶段一：原始事件层

- 增加 `RawEventStore`、分区目录和原始事件模型。
- 自有 watcher 增加稳定 `sourceEventId`。
- 写入链路调整为隐私校验后先提交原始事件。
- 不导入现有开发数据库中的事件。

### 阶段二：独立投影器

- 把 heartbeat 合并从 `EventStore.insertHeartbeat` 迁入 `EventProjector`。
- 增加 checkpoint 和崩溃恢复。
- 支持从原始分区重建测试数据库。
- 验证重建时间线与现有时间线的预期差异。

### 阶段三：分区封存与查询

- 启用月分区轮换、manifest 和 catalog。
- 增加原始事件只读查询 API。
- 增加桌面容量、完整性和投影延迟状态。

### 阶段四：正式切换

- 原始写入失败时禁止投影-only 降级。
- 正式声明原始事件层为事实源。
- 将 `aw.db`、Wiki 和 Lucene 标记为可重建派生数据。
- 更新架构、隐私、内容、文件和 Wiki 正式规格。

## 21. 测试与验收

### 21.1 不删除与不可变

- heartbeat 合并后，所有原始 heartbeat 仍存在。
- 投影重建、Wiki 清理、bucket 删除和文件目录移除均不改变原始事件计数。
- 原始层业务代码不执行针对 `raw_events` 的 `UPDATE` 或 `DELETE`。
- 跨月封存前后事件数量与 SHA-256 校验一致。

### 21.2 隐私

- 使用秘密标记构造 UIA 正文，确认所有原始分区、WAL、manifest、日志和投影均不包含标记。
- 内容禁止字段在原始提交之前返回 `422`。
- 文件正文秘密标记不出现在原始分区、`file-watch.db` 或 AW 投影。
- API key、token 和 Authorization header 不进入原始层。

### 21.3 故障恢复

- 原始事务提交后、投影前崩溃，重启后能补齐投影且不重复原始事件。
- 分区封存中断后可以继续恢复，不删除旧分区。
- catalog 损坏时可以通过只读扫描 manifest 重建。
- `aw.db` 删除后可以从原始分区完整重建兼容时间线。
- Wiki 和 Lucene 删除后可以从投影重新生成。

### 21.4 长期运行

- 使用高频 heartbeat 压测至少一个月等价数据量。
- 验证跨分区分页顺序稳定且无遗漏、无重复。
- 磁盘达到警告阈值时不删除事件。
- 磁盘不足无法写入时，采集进入 blocked，且不会产生 projection-only 事件。

## 22. 预期代码影响

| 模块/文件 | 预期变化 |
|-----------|----------|
| `self-analyst-aw` | 新增原始存储、分区 catalog、投影器、checkpoint 和原始查询服务 |
| `EventStore` | 收敛为 ActivityWatch 投影存储，不再承担原始事实保存 |
| `HeartbeatController` | 先写原始层，再触发幂等投影，返回原始/投影状态 |
| `EventController` | 批量原始事务、稳定事件 ID 和导入幂等 |
| `DataImporter` | 引入导入会话和稳定 ordinal，保证重复导入幂等 |
| `WindowWatcher/AfkWatcher` | 生成并在重试时复用稳定 `sourceEventId` |
| `ContentWatcher` | 生成稳定事件 ID，保持内容 v2 白名单 |
| `FileWatcher` | 生成稳定事件 ID，保持 metadata-only schema |
| `AppSession` | 启动顺序调整为原始恢复 → 投影恢复 → watcher → Wiki |
| `DesktopStatusController` | 展示容量、分区、完整性和投影延迟 |
| `WikiWorker` | 记录原始覆盖范围和事实构建版本，支持重建 |

## 23. 风险与取舍

### 23.1 存储持续增长

永久保留逐 heartbeat 事件会持续占用磁盘。分区降低单库维护风险，但不会消除总容量增长。项目必须
提供增长速度可视化、磁盘告警和备份指导，不能承诺固定磁盘占用。

### 23.2 两层存储增加复杂度

原始层与投影层无法依赖单个 SQLite 事务完成跨库原子提交，因此必须采用“原始先提交、投影可重试”
的一致性模型，并为所有投影操作提供幂等性。

### 23.3 第三方客户端幂等限制

没有稳定来源事件 ID 的第三方客户端无法完美区分网络重试与合法重复事件。为了满足永久保留，原始
层倾向于多保留而不是误删；统计投影可以单独应用重复折叠策略。

### 23.4 永久保留与隐私风险

标题和文件路径本身也可能敏感。永久保留提高了本地访问控制、导出确认、备份加密和数据边界测试的
重要性。任何扩大采集字段的变更都必须先更新隐私规格并经过明确审查。

## 24. 待确认决策

- 第一版使用月度 SQLite 分区，还是直接使用面向冷存储的压缩格式。
- 原始逐 heartbeat 查询是否只提供桌面 API，还是也扩展 AQL。
- 用户手工触发的原始数据不可逆销毁是否需要单独产品能力。
- 原始目录是否默认启用静态加密，以及密钥恢复方式。
- 是否保留每次第三方 HTTP 投递，还是只保留解析成功的事件语义值。

在这些决策确认并完成实现前，本文件仅作为设计方案，不能替代现行 `openspec/specs/` 主规格。
