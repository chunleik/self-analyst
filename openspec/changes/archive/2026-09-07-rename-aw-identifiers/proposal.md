## Why

采集桶、Wiki 时间线桶和投影库文件仍使用 ActivityWatch 品牌前缀（`aw-watcher-*`、`aw.db`、`aw-data`），与产品自身身份不一致。当前版本从未发布，可以一次性改成产品内命名，不必做本地数据迁移。

## What Changes

- **BREAKING** 本产品采集器写入的 bucket ID 与 client 去掉 `aw-`，采集侧改为 `watcher-{kind}_{hostname}` / `watcher-{kind}`（window、afk、content、file）。
- **BREAKING** Wiki 时间线投影桶改为 `wiki-{hourly|halfday|daily}_{hostname}`，client 为 `wiki`；不加 `watcher-`（派生摘要，不是采集）。
- **BREAKING** 嵌入式投影库文件由 `aw.db` 改为 `events.db`（含重建/备份后缀）；默认数据目录由 `aw-data` 改为 `events`，原始分区仍在 `{aw.data-dir}/raw`。
- 内容事件策略、来源分类、Wiki 事实查询、桶列表隐藏规则改为认新前缀/client；为兼容 HTTP 导入，仍识别外部 `aw-watcher-*` ID 与 client。
- 不迁移、不改写既有本地库中的旧 ID；不重命名配置键 `aw.port` / `aw.mode` / `aw.base-url` / `aw.data-dir` / `aw.raw.*`；不改 HTTP `/api/0`；不改 SQLite 表名；不改 Maven 模块 `self-analyst-aw` 与包名 `com.selfanalyst.aw`。

## Capabilities

### New Capabilities

- （无）本次只调整既有存储与采集标识，不引入新能力。

### Modified Capabilities

- `content-event-persistence`: 内容策略与历史净化的 bucket 前缀/client 改为 `watcher-content_` / `watcher-content`，并继续匹配导入用的 `aw-watcher-content_*`。
- `llm-wiki`: 事实构建默认查询 `watcher-window_*`、`watcher-afk_*`、`watcher-content_*`；时间线投影写入 `wiki-hourly_*` 等，不再使用 `aw-watcher-wiki-*`。
- `file-metadata-collection`: 文件采集桶默认 ID/client 改为 `watcher-file_{hostname}` / `watcher-file`。
- `raw-event-retention`: 派生投影库文件名改为 `events.db`，重建旁路文件使用对应前缀。

## Impact

- 采集写入：`WindowWatcher`、`AfkWatcher`、`ContentWatcher`、`FileWatcher`。
- 识别与策略：`EventIngestionService`、`HeartbeatIngestionService`、`ContentEventPolicy`、`BucketController` 隐藏前缀。
- 消费：`WikiFactBuilder`、`WikiSummaryWatcher`、`SummaryService`。
- 存储路径：`Database`、`AwServer`、`ProjectionRebuildService` 及默认 `aw.data-dir`。
- 测试、现行 OpenSpec 主规格正文，以及 `README.md`、`PRIVACY.md`、`docs/architecture.md`。
- HTTP `/api/0`、Agent 工具路径、`SPEC-AW-*` 追溯号、SQLite 表名、配置键名保持不变。
- 模块/包重命名不在本次范围，可后续单独 change。
- 因从未发布，不提供旧桶 ID 或旧 `aw.db` 的本地迁移。
