## Why

当前 `aw.*` 配置同时控制 SelfAnalyst 自研事件服务、标题采集和永久原始事件层，名称容易让用户误认为这些能力属于外部 ActivityWatch。此前模块、存储标识与运行时文案已改为事件服务用语，本次完成用户配置命名统一，并按用户选择直接移除旧名称，要求手动更新配置及启动环境。

## What Changes

- **BREAKING** 将当前全部 16 个受支持 `aw.*` 配置键改为 `events.*`，对应 15 个受支持环境变量改为 `EVENTS_*`；不提供旧名称别名、自动迁移或双读。
- **BREAKING** 标题采集开关使用 `events.collection.title.enabled`，轮询间隔使用 `events.collection.title.pollMs`。将开关置于 `enabled` 叶子，避免 `title` 同时作为 TOML 布尔值和表；完整性校验范围使用 `events.raw.integrity.startupScope`，取值仍为 `latest/all`。
- **BREAKING** 结构化配置 API 的事件服务分组由 `aw` 改为 `events`，采集分组使用 `collection.title.enabled/pollMs`；配置元数据、有效配置、重启差异及 Agent 配置工具同步展示和使用新键。
- 新增已移除名称诊断：启动或配置提交遇到本次移除的已知旧键/旧环境变量时拒绝继续，提示对应新名称，避免静默回退默认目录或采集开关。新旧名称并存也拒绝，不进行优先级选择。
- 按实施中确认的范围补齐启动完整性检查：此前该配置只被解析，没有接入事件存储；本次让 latest/all 实际选择检查分区，校验失败时隔离对应分区并阻止启动，不修改或删除原始文件。
- 保留各项默认值、类型、合法范围、加载优先级、事件服务组件归属与重启规则；`events.port` 仍只接受 TOML/default，由 Java 解析并通过握手文件通知桌面壳。
- 更新受影响的运行配置访问器、默认资源、脚本、用户文档和主规格；HTTP 协议、事件 payload、bucket 标识、数据文件与数据库布局不变。旧 OCR/音频键继续遵循原有废弃处理，不因前缀改名恢复能力。

本次是配置契约变更，并非只修改文档或迁移规格基线。

## Capabilities

### New Capabilities

无独立新增 capability；新诊断归入既有用户配置能力。

### Modified Capabilities

- `user-configuration`：定义新配置键及环境变量的完整映射、旧名称拒绝策略、标题配置结构、模板与配置 API 命名，并调整原先禁止改名和明确保留 `aw.*` 的条款。
- `event-query-tools`：事件查询超时改由 `events.timeout` 提供，保留既有工具方法和 HTTP 请求语义。
- `raw-event-retention`：投影路径引用改为 `{events.data-dir}/events.db`，明确配置改名不迁移或重建已有原始事件及投影；补齐按范围执行的启动完整性检查与失败隔离行为。

## Impact

- Java 配置声明、解析、校验、存储、运行配置差异、桌面配置控制器、ConfigTools，以及应用和各查询服务对配置访问器的调用。
- `EventServer`、原始事件存储及 catalog 的启动校验接线、只读检查和结果记录；相应原始层单元测试及隔离启动测试。
- `application.properties`、TOML 模板、打包/桌面启动验收脚本、安装脚本中的配置示例及 `.github/ISSUE_TEMPLATE/bug_report.yml`。
- README、架构说明、脚本文档、原始层基准文档和上述三个主规格；归档设计保留其历史名称。
- 已有 `config.toml`、环境变量和调用结构化配置 API 的客户端需要手动更新；本次不读取、改写或迁移实际用户配置和数据目录。
- 无新增依赖；验证覆盖配置三种写入入口、环境兜底、TOML 往返、旧名称拒绝、原始目录保护、事件查询和打包启动。
