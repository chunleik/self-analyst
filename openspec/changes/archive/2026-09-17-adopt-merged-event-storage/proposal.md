## Why

当前约三天的本机数据已累计约 41 万条心跳、343 MiB 文件；连续相同状态虽然在时间线合并，永久原始层、索引和逐条投影关联仍随每次心跳增长。用户已选择采用 ActivityWatch 的合并存储方式，以活动区间作为持久化事实，降低长期磁盘增长。

## What Changes

- **BREAKING**：嵌入式事件服务改为直接事务性保存合并事件；相邻、data 等价且满足 pulsetime 的 heartbeat 更新同一记录，保留高频采集，不再逐心跳写入 raw 分区和永久来源关联。
- 明确乱序、时钟回拨、休眠、采集中断、重试和并发写入语义；非 heartbeat 的 events/导入保留独立事件语义。
- **BREAKING**：取消逐心跳永久保留、原始查询/导出和从原始层重建当前事件库的保证。旧原始 API 明确报告能力退役，不以合并事件冒充原始记录；普通事件查询、统计、Wiki 和合并事件导出继续可用。
- 增加当前双层存储到紧凑事件库的可恢复迁移，校验后切换；旧库保留为迁移备份，只有用户显式执行清理才能释放备份占用，并明确其不可逆影响。
- 存储状态区分活动库、临时文件和历史备份，报告增长及磁盘压力；迁移后的事件库成为需要备份的权威数据。
- 更新隐私边界相关规格、配置提示、双语 README 和架构说明。这是行为修改，不是既有行为的文档基线迁移。

## Capabilities

### New Capabilities

- `merged-event-storage`：合并事件权威存储、事务与有界重试去重、旧格式迁移/恢复/清理、容量可观测性及退役接口行为。

### Modified Capabilities

- `user-configuration`：对齐已批准的 raw 配置退役和当前存储/导出配置。
- `llm-wiki`：事实直接读取合并事件，覆盖元数据不再依赖 raw 投影延迟。
- `runtime-storage`：扩展格式准入，旧格式 1 的目标库验证后、活动库替换前发布格式 2，保留未知格式拒绝及数据根独占约束。
- `raw-event-retention`：移除永久逐心跳存储及其原始查询、封存、重建、启动检查契约，由合并事件存储能力接替。
- `content-event-persistence`：标题事实直接进入合并事件存储，Wiki 读取合并记录，保留内容 v2 隐私校验。
- `file-metadata-collection`：文件 heartbeat 使用相同合并存储机制，保留字段白名单和当前监控根的查询约束。
- `agent-document-generation`：直接导出区分合并事件、文件元数据和 Wiki 摘要；新原始导出请求明确不可用，已生成成果仍可下载。

## Impact

- `self-analyst-events`：ingestion、store、projection、raw、controller、export、watcher 的写入和启动链路及回归测试。
- `self-analyst-app`：配置、存储格式识别、桌面状态/维护接口与 UI、文档数据导出、Wiki 消费和集成测试；`self-analyst-content`、`self-analyst-file` 的采集/重试兼容验证。
- `openspec/specs/`、`docs/architecture.md`、相关配置与维护文档、`README.md` 和 `README.zh-CN.md`。不增加外部事件服务依赖，不改变外部 ActivityWatch 服务的数据。
- 历史逐心跳精度不再是新格式的产品承诺；已有数据迁移不得直接修改真实用户库作为开发实验，不自动删除旧库、不自动导入旧开发期 `aw.db`。
