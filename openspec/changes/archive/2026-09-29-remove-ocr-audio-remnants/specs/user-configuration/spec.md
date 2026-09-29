## MODIFIED Requirements

### Requirement: SPEC-TOML-RENAME-002 已移除名称拒绝与手动更新

启动解析、raw/结构化配置保存、Agent 配置写入及使用配置文本的连接测试 SHALL 检查 SPEC-TOML-RENAME-001 列明的旧键与旧环境变量。只要这些旧名称显式存在，即使值为空、被 TOML 遮蔽或与新名称同时出现，系统 MUST 拒绝该次配置解析或提交，并指出旧名称、对应新名称和手动修改/移除要求；MUST NOT 读取旧值作为有效配置，也 MUST NOT 自动改写输入。

启动失败 SHALL 发生在事件服务、采集器或数据目录初始化之前。raw/结构化 HTTP 配置提交与文本连接测试 SHALL 返回 400；Agent 工具 SHALL 返回明确失败。失败 MUST 保留磁盘文件和现有运行配置。诊断 SHALL 仅展示名称与来源，不包含配置值或完整配置文本。

该规则 SHALL 只针对表内已移除名称及对应结构化字段。普通未知键仍按现有未知键规则处理；旧采集功能键不再享有专用兼容规则，与普通未知键一致：加载不生效，raw 保存报告 unknownKeys，结构化保存忽略未知字段且不写入。旧 raw 开关/TTL/容量/删除键 SHALL 作为退役配置忽略，不控制合并存储，也不能触发自动删除历史。

#### Scenario: 旧目录配置阻止启动回退
- **WHEN** 启动 TOML 中仍包含 `aw.raw.dir`
- **THEN** 启动报出应改为 `events.raw.dir` 的错误，不使用默认 raw 目录启动采集，不改动配置或原始分区

#### Scenario: 新旧名称并存
- **WHEN** 用户同时提交 `aw.mode` 和 `events.mode`，或进程同时设置 `AW_MODE` 和 `EVENTS_MODE`
- **THEN** 即使两者值相同也拒绝解析，要求移除旧名称，不选择任一名称优先

#### Scenario: 被遮蔽的旧环境变量
- **WHEN** TOML 已配置 `events.collection.title.enabled=false`，启动环境仍定义 `AW_COLLECTION_CONTENT`
- **THEN** 启动拒绝并提示更新环境变量；不因新 TOML 已覆盖而忽略旧名称

#### Scenario: 结构化旧事件分组
- **WHEN** 客户端提交 `{"aw":{"mode":"embedded"}}` 或 `{"collection":{"content":false}}`
- **THEN** 返回 400 及对应新配置名称，原磁盘与运行配置不变

#### Scenario: 旧名称仅出现在注释中
- **WHEN** 合法 TOML 的注释中包含 `aw.mode`，但实际赋值全部使用新名称且环境干净
- **THEN** 允许保存和解析，raw 往返保留原注释

#### Scenario: 已移除采集功能仍被忽略
- **WHEN** 旧配置包含 `aw.ocr.engine` 或 `aw.audio.enabled`，但没有本次改名表中的旧键
- **THEN** 不因这些键触发本次改名错误；加载不生效，raw 保存将它们列为 unknownKeys，结构化保存忽略相应未知字段且不写入

#### Scenario: 未支持的永久层关闭配置
- **WHEN** 用户提交 `aw.raw.enabled=false` 或 `events.raw.enabled=false`，或在任一前缀下配置 retentionDays、maxPartitions、autoDelete
- **THEN** 系统忽略这些退役保留策略，不关闭合并记录或自动删除历史
