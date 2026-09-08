## Context

动机与范围见 [proposal.md](proposal.md)。现有 `SupportedKeys` 声明 16 个 `aw.*` 键，`ConfigResolver` 显式映射 15 个 `AW_*` 环境变量；端口没有环境入口。配置被默认资源、TOML 生成、配置 API、ConfigTools、组件重启差异和启动脚本共同消费，因此需要设计跨入口一致性及手动更新边界。

当前规格有两处必须显式调整的约束：`SPEC-TOML-FMT-004` 要求保留 `aw.*`；`SPEC-TOML-NON-001..005` 禁止既有键重命名。本 change 的增量规格修改这些条款，并保留其它配置键的稳定性。现有两个含 aw 的 Scenario 标题按 OpenSpec 对修改块的场景保留要求继续保留，场景正文使用新名称；这些标题作为规格追溯文本，不是运行配置入口。

用户已选择直接移除旧名称。以下“旧名对照”只服务于报错和手动更新，不形成兼容解析。

## Goals / Non-Goals

**Goals:**

- 配置声明、解析、展示、写入和运行差异统一使用新名称，所有入口产生同一有效配置。
- 在启动数据服务前识别旧名，避免旧目录被忽略后创建新的默认库，或旧关闭开关被忽略后恢复默认采集。
- 保持实际数据目录、协议及功能行为；让标题开关和轮询间隔能够共同表达为合法 TOML。

**Non-Goals:**

- 不更改第三方 ActivityWatch 协议、`/api/0`、`/0`、来源 ID、事件数据中的 content 字段、数据库文件名或存储布局；不重命名 `SPEC-AW-*` 追溯号。
- 不扩大 Agent 可写配置集合，不实现事件组件热更新，不调整超时、端口、查询限制或磁盘阈值默认值。
- 不做全项目 camelCase/kebab-case 清理，不重命名采集模块及 `ContentWatcher` 等数据领域类型，不调整非配置 API 的状态 JSON 契约。
- 不改写实际用户文件，不新增配置历史、自动回滚或数据迁移。归档文档保持历史原貌。

## Decisions

### 1. 单一新命名空间及标题分组

完整映射以 [用户配置增量规格](specs/user-configuration/spec.md) 的 `SPEC-TOML-RENAME-001` 为准。常规键仅将首段 `aw` 替换为 `events`；标题和完整性键按映射作完整键替换。

标题采用 `events.collection.title.enabled` 与 `events.collection.title.pollMs`。最初讨论的 `title` 布尔值与 `title.pollMs` 会让同一 TOML 路径同时充当标量和表；因此将开关落在 `enabled` 叶子。示例：

```toml
[events.collection.title]
enabled = false
pollMs = 800

[events.raw.integrity]
startupScope = 'all'
```

环境名分别为 `EVENTS_COLLECTION_TITLE_ENABLED`、`EVENTS_COLLECTION_TITLE_POLL_MS` 和 `EVENTS_RAW_INTEGRITY_STARTUP_SCOPE`。旧轮询环境变量实际是 `AW_CONTENT_POLL_MS`，不能只替换前缀或从旧点分键机械推导。

备选为只改 README、保留旧前缀，或把采集拆成独立顶级域；前者无法完成用户配置统一，后者扩大本次组织结构变更。其它后缀保持原样。

### 2. 声明和消费同步替换

在 `SupportedKeys`、`application.properties`、`ConfigResolver`、`Config`、`RawConfigValidator`、`RawIntegrityPolicy`、`ConfigPolicy` 与 `TomlSupport` 中更新新名称、说明、分组和错误文案。`ConfigPolicy` 仍把全部 `events.*` 归入现有 events 组件，并按原有规则要求重启。

Java 配置访问器按含义更名：`awBaseUrl/awTimeout/awEmbedded/awPort/awDataDir/awRaw*` 改为对应 `events*`，完整性访问器使用 `eventsRawIntegrityStartupScope`；`collectContent/contentPollIntervalMs` 改为 `collectTitle/titlePollIntervalMs`。同步应用启动、配置投影、桌面控制器、查询服务与测试的编译期调用。事件 payload 中的 content 名称保持不变。

原始目录保护统一使用候选有效配置，检查 `events.raw.dir` 及其由 `events.data-dir` 派生的实际路径，覆盖控制器和配置应用服务中的双重入口。不得遗漏控制器直接读取环境变量的路径。更名后的路径值及工作目录相同就继续使用现有分区，不能把“改键名”当成“换目录”。

### 3. 显式拒绝旧名，先诊断再解析

维护有限的旧键/旧环境名到新名称对照，用于产生不含值的校验错误；不归一化旧值、不把旧名加入受支持表，也不以 `aw.*` 或 `AW_*` 通配拒绝整个前缀。

启动时在 Config 有效值解析、服务监听与采集/数据目录初始化前检查用户覆盖和进程环境，不能被现有加载降级逻辑捕获后继续使用默认值。空字符串和新 TOML 遮蔽的旧环境变量也要求手动移除；这是直接移除契约的一部分。

raw、结构化和 Agent 写入在过滤未知键前检查本次移除的名称，并在提交最终候选配置时复用检查，避免旧键先被过滤而返回假成功。结构化旧 `aw` 分组仍可被识别为报错来源，但不得读取为有效设置；旧 `collection.content` 与 `collection.content.pollMs` 同样报错。已废弃 OCR/音频字段仍走原有忽略路径；不要与本次仍在使用、现被改名的键混为一类。

配置 HTTP 提交和带文本连接测试的此类错误统一为 400；Agent 返回明确错误；任何失败保留原文件和运行版本。raw GET 仍用于逐字读取文件，不把注释中的旧名当作配置，也不在读取时自动改名。

保留策略的 `enabled/retentionDays/maxPartitions/autoDelete` 在 `aw.raw.*` 和 `events.raw.*` 下都禁止；这份禁止表与本次 16 个已移除键分开维护。`AW_PORT` 从未受支持，`EVENTS_PORT` 也没有入口，均不成为新环境配置或本次旧名错误对象。

备选为保留兼容别名，用户已明确排除。另一个备选是把旧键当普通未知键静默忽略，但这可能改变数据目录及采集状态，因此采用拒绝诊断。

### 4. 配置 API 和工具一次切换

`GET/PUT /desktop/config` 的事件分组由 `aw` 改为 `events`，保留现有 mode、port、dataDir、webUrl 字段形式；顶层 collection 分组继续存在，标题字段改为 title 对象，其中包含 enabled 与 pollMs。旧客户端提交命中已移除键时返回 400 及改名指引。

raw supportedKeys、有效配置 values、inheritedFrom、restartRequired、组件差异和 ConfigTools 的返回/说明只公开新键。ConfigTools 仍只允许原先可写键对应的新名称，不因为模板列出其它项就扩大 Agent 权限。其它 API 路径与非配置状态字段不变。

### 5. 脚本与文档中的真实配置入口

更新 `check-packaged-jar.ps1` 和 `check-desktop-autostart.ps1` 的 TOML、子进程环境注入及清理规则，继续显式关闭全部采集并使用临时目录。子进程需要清理可能继承的旧环境名，但不能修改用户或机器的全局环境。

安装脚本中的 SelfAnalyst 配置示例同步采用新名称；已有旧配置路径的生成行为不借本次重写为另一套安装机制。指向第三方 ActivityWatch 的 `AW_INSTALLED`、脚本内 `AW_URL`、`start-aw` 等保留其真实第三方含义，不是 SelfAnalyst 配置变量，不能全局替换。

更新 README、docs/README、docs/architecture、docs/benchmarks/raw-event-retention、scripts/README 及缺陷模板中的有效配置说明。手动改名指引集中提供完整映射，并强调同时更新启动环境和标题结构，不能仅作 `aw.` 字符串替换。历史档案、旧名错误测试及第三方协议引用可以继续包含 aw。

### 6. 补齐启动完整性检查

实施核查确认 `RawIntegrityPolicy` 只被配置层读取，未传给 `EventServer`；用户已确认本次补齐。应用将配置枚举转换为检查全部分区的布尔参数传给事件服务，保留原有构造入口并默认 latest，避免 events 模块反向依赖 app 模块。

原始存储恢复中断封存后，在投影恢复和数据库/HTTP 服务初始化前执行检查；latest 取 catalog 列表的最大月份，all 检查全部，空列表直接返回。复用原始存储的只读 SQLite 统计和文件摘要方法，核对 catalog 数量/ID/时间边界；SEALED 分区额外只读核对安全解析的 manifest、版本、月份、文件名、统计信息、大小及 SHA-256。ACTIVE 分区不以磁盘主文件大小判断一致性，因为 WAL 可能尚未合并。检查成功只更新 catalog 的 verifiedAt，不重写封存 manifest。

检查失败只隔离 catalog 记录，释放已打开资源并抛出不含路径/payload/底层原因的专用启动校验异常。应用对该异常终止启动，不能落入现有事件服务启动失败的降级分支，也不能发布端口握手。既有历史隔离分区被范围选中时同样失败；本次不提供解除隔离或自动修复功能。

验证以两个跨月分区为基线：损坏旧封存 manifest 后 latest 能检查最新健康分区，all 必须失败并保留原文件；再覆盖最新活动分区的统计不一致、空目录和应用级失败传播。备选为只记录配置而不接线，用户已选择排除。

## Risks / Trade-offs

- [旧路径或关闭开关被当作未知键忽略] → 解析前拒绝，加入真实启动失败且未创建默认事件目录的回归测试。
- [标题开关与子键冲突] → 使用 enabled 叶子，验证模板赋值、结构化生成和 raw 往返均可同时配置两项。
- [环境变量存在不规则旧名或控制器自行读取] → 使用完整 15 项映射，覆盖 `AW_CONTENT_POLL_MS` 与两处目录来源解析。
- [手动修改原始目录值造成数据不可见] → 文档要求保留路径值，回归验证相同目录重启复用分区、已有分区后仍禁止变更有效目录。
- [对 aw 作无边界字符串替换误伤 raw、第三方脚本或历史档案] → 按完整键/标识符和文件职责逐项修改，残留扫描逐项分类，不把零字符串出现当作验收条件。
- [本次直接移除使旧配置无法启动] → 错误给出旧名和新名，随变更提供对照表；用户手动修改后重启。
- [历史损坏现在会阻止启动，all 检查耗时随历史增长] → 默认 latest，显式 all 才检查全部；保留原文件并报告隔离月份，文档说明检测范围与失败处理。

## Migration Plan

1. 在功能分支实现并验证一次完整切换；默认模板及发布包只输出新名称，不设置过渡期双读。
2. 用户停止旧进程，按对照表手动修改 `./data/config/config.toml`，同时更新实际启动上下文中的环境变量。保留原有路径及设置值，重新检查标题开关与轮询层级。
3. 新版本先校验名称，再启动数据服务。旧名未移除时拒绝并给出诊断；全部改完后沿用现有目录，无数据迁移步骤。
4. 若需要回退程序版本，由用户按同一对照表反向调整配置和环境；本次不提供自动配置回滚，不对数据库执行回退迁移。
