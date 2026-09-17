## MODIFIED Requirements

### Requirement: SPEC-TOML-RAW-001 原始事件配置集合
系统 SHALL 使用 `events.export.maxRangeDays`、`events.storage.lowDisk.warnBytes` 和 `events.storage.lowDisk.blockBytes` 配置导出范围及活动库空间阈值。`events.raw.*` SHALL 标为退役并提示不再控制在线存储，只有 `events.raw.dir` 保留用于定位旧迁移输入。系统 MUST NOT 提供关闭合并事实记录或自动删除历史的普通保留策略。

#### Scenario: 默认原始事件配置
- **WHEN** 用户没有覆盖事件存储配置
- **THEN** 旧 raw 迁移输入默认位于 `{events.data-dir}/raw`，合并事件导出范围默认为 31 天，活动库默认 10 GiB 告警、1 GiB 阻断；不创建新的原始分区

#### Scenario: 禁止关闭永久保存
- **WHEN** 用户保留旧 raw 开关、保留天数、最大分区数或自动删除键
- **THEN** 这些键不控制新格式行为，不恢复永久逐心跳记录，也不触发历史删除

### Requirement: SPEC-TOML-RAW-002 原始事件配置校验
旧迁移目录 SHALL 接受可解析的本地路径。当前导出范围和磁盘阈值 MUST 为正整数，阻断阈值 MUST 小于告警阈值；无效当前配置 SHALL 拒绝整次保存并保持旧文件。退役 raw 数值和完整性枚举 SHALL 不再控制新格式，既有 TOML 类型检查保持生效。新格式 SHALL 独立执行事件库健康检查和迁移校验。

#### Scenario: 磁盘阈值关系错误
- **WHEN** `events.storage.lowDisk.blockBytes` 大于或等于 `events.storage.lowDisk.warnBytes`
- **THEN** 配置保存返回校验错误，磁盘上的 TOML 不变

#### Scenario: 无效完整性策略
- **WHEN** 旧 `events.raw.integrity.startupScope` 包含已不生效的字符串值
- **THEN** 系统提示该键已退役，不根据它关闭当前事件库或迁移完整性校验

### Requirement: SPEC-TOML-RENAME-002 已移除名称拒绝与手动更新

启动解析、raw/结构化配置保存、Agent 配置写入及使用配置文本的连接测试 SHALL 检查 SPEC-TOML-RENAME-001 列明的旧键与旧环境变量。只要这些旧名称显式存在，即使值为空、被 TOML 遮蔽或与新名称同时出现，系统 MUST 拒绝该次配置解析或提交，并指出旧名称、对应新名称和手动修改/移除要求；MUST NOT 读取旧值作为有效配置，也 MUST NOT 自动改写输入。

启动失败 SHALL 发生在事件服务、采集器或数据目录初始化之前。raw/结构化 HTTP 配置提交与文本连接测试 SHALL 返回 400；Agent 工具 SHALL 返回明确失败。失败 MUST 保留磁盘文件和现有运行配置。诊断 SHALL 仅展示名称与来源，不包含配置值或完整配置文本。

该规则 SHALL 只针对表内已移除名称及对应结构化字段。普通未知键仍按现有未知键规则处理；旧 OCR 键及 `aw.audio.*` 仍按已移除功能规则接受但忽略，不映射为 `events.*`，不恢复受支持状态。旧 raw 开关/TTL/容量/删除键 SHALL 作为退役配置忽略，不控制合并存储，也不能触发自动删除历史。

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
- **THEN** 不因这些废弃键触发本次改名错误，仍不启用 OCR 或音频能力，模板与支持键不暴露它们

#### Scenario: 未支持的永久层关闭配置
- **WHEN** 用户提交 `aw.raw.enabled=false` 或 `events.raw.enabled=false`，或在任一前缀下配置 retentionDays、maxPartitions、autoDelete
- **THEN** 系统忽略这些退役保留策略，不关闭合并记录或自动删除历史

### Requirement: SPEC-TOML-RENAME-003 标题配置结构与校验范围

标题采集 SHALL 使用 `events.collection.title.enabled` 布尔开关和 `events.collection.title.pollMs` 整数间隔；默认值分别为 true 与 500 毫秒，间隔范围及越界处理 SHALL 保持既有行为。标题采集 SHALL 继续只保存标题事实，不因改名采集正文、控件树、截图、OCR 或音频。旧 `events.raw.integrity.startupScope` SHALL 标为退役；当前事件库健康检查和旧格式迁移校验独立执行，不受该旧枚举控制。

模板、结构化生成和 raw 元数据中的赋值 SHALL 允许同时启用标题开关与轮询间隔，不产生 TOML 标量/表冲突。

#### Scenario: 标题开关与间隔共同保存
- **WHEN** 用户在 `[events.collection.title]` 表内设置 `enabled=false` 和 `pollMs=800`
- **THEN** 两项独立解析为对应新键；结构化保存后再次解析得到相同覆盖值

#### Scenario: 完整性校验全部分区
- **WHEN** 用户设置 `events.raw.integrity.startupScope='all'`
- **THEN** 该旧键不控制新格式；迁移仍校验已有输入，新库仍执行健康检查，错误 TOML 类型按通用规则拒绝
