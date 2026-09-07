## 1. 采集写入标识

- [x] 1.1 将 `WindowWatcher`、`AfkWatcher` 的 name/bucketId/client 改为 `watcher-window` / `watcher-afk` 及 `watcher-{kind}_{hostname}`，并更新对应 watcher 测试或构造断言，使新建桶不再使用 `aw-watcher-` 前缀
- [x] 1.2 将 `ContentWatcher` 的 bucketId/client 改为 `watcher-content_{hostname}` / `watcher-content`，跑 `ContentWatcher` 相关单元测试确认 heartbeat 目标桶为新 ID
- [x] 1.3 将 `FileWatcher` 的 bucketId/client 改为 `watcher-file_{hostname}` / `watcher-file`，跑 `self-analyst-file` 中 `FileWatcherTest` 确认写入新桶

## 2. 来源识别与内容策略

- [x] 2.1 更新 `EventIngestionService` 与 `HeartbeatIngestionService`：本产品前缀 `watcher-{kind}_` 与导入前缀 `aw-watcher-{kind}_` 均映射到同一 `RawEventSource`，并补充/修改分类测试
- [x] 2.2 更新 `ContentEventPolicy` 的前缀与 client 常量，使 `watcher-content` 与 `aw-watcher-content` 都触发 v2 策略；跑 `ContentEventPolicyTest` 覆盖两种前缀
- [x] 2.3 更新 `BucketController` 默认隐藏前缀为 `watcher-content_` 与 `aw-watcher-content_`，跑 `AwServerBucketVisibilityTest` 确认默认列表隐藏内容桶、完整列表仍可见
- [x] 2.4 更新内容策略 HTTP/导入测试（`AwServerContentPolicyTest`、`DataImporterContentPolicyTest`、`ContentRawPrivacyIntegrationTest`）中的桶 ID/client，使新写入用例用 `watcher-content_*`，并保留至少一条 `aw-watcher-content_*` 导入用例

## 3. Wiki 与摘要消费

- [x] 3.1 更新 `WikiFactBuilder` 默认查询 `watcher-window/afk/content_{hostname}`，并对 `aw-watcher-*` 同类桶保持可识别；跑 `WikiFactBuilderTest`
- [x] 3.2 更新 `WikiSummaryWatcher`：桶 ID 改为 `wiki-hourly|halfday|daily_{hostname}`，client 改为 `wiki`；补充或修改测试断言新 ID 且不以 `watcher-` 开头
- [x] 3.3 更新 `SummaryService` 等硬编码窗口/AFK 桶名，跑相关桌面摘要测试确认查询新桶

## 4. 投影文件与默认目录

- [x] 4.1 将 `Database` / `AwServer` / `ProjectionRebuildService` 的投影文件从 `aw.db` 改为 `events.db`，重建/备份前缀同步为 `events.db.rebuilding-*` 与 `events.db.backup-*`；跑 `ProjectionRebuildServiceTest`、`AwServerInitializationOrderTest`、`RawFirstEnablementBoundaryTest`
- [x] 4.2 将 `SupportedKeys`、`Config` 默认值与 `DesktopConfigController` 回退路径中的 `aw-data` 改为 `events`（`aw.raw.dir` 默认为 `{aw.data-dir}/raw`），配置键名保持 `aw.data-dir`；跑配置相关测试确认默认路径变化且键名未改
- [x] 4.3 全库替换测试夹具中的 `aw.db` / `aw-data` 工作副本（不含 `docs/archive`），确认测试不再依赖旧文件名作为当前投影

## 5. 规格、文档与回归

- [x] 5.1 把本 change 的 delta 同步进 `openspec/specs/` 下 `content-event-persistence`、`llm-wiki`、`file-metadata-collection`、`raw-event-retention` 主规格，并更新 `ActivityWatchTools` 工具说明示例为 `watcher-window_<hostname>`
- [x] 5.2 更新用户向文档 `README.md`、`PRIVACY.md`、`docs/README.md`、`docs/architecture.md` 中的 `aw.db` / `aw-data` 表述；不改 `docs/archive/`
- [x] 5.3 运行 `mvn -pl self-analyst-aw,self-analyst-wiki,self-analyst-file,self-analyst-app -am '-Dsurefire.failIfNoSpecifiedTests=false' test`，确认与标识、投影路径相关的模块测试通过
