## ADDED Requirements

### Requirement: SPEC-TOML-RAW-001 原始事件配置集合
系统 SHALL 支持 `aw.raw.dir`、`aw.raw.query.maxRangeDays`、`aw.raw.query.maxPageSize`、`aw.raw.lowDisk.warnBytes`、`aw.raw.lowDisk.blockBytes`、`aw.raw.integrity.verifyOnStartup` 和 `aw.raw.projector.batchSize`。原始事件能力在嵌入式模式 SHALL 固定启用，MUST NOT 提供关闭永久保存、按天保留或自动删除分区的普通用户配置。

#### Scenario: 默认原始事件配置
- **WHEN** 用户没有覆盖原始事件配置
- **THEN** 原始目录派生为 `{aw.data-dir}/raw`，使用月度分区、31 天查询范围上限、1000 条单页上限、10 GiB 告警阈值、1 GiB 阻断阈值、启动校验最新分区和 1000 条投影批量大小

#### Scenario: 禁止关闭永久保存
- **WHEN** 用户尝试配置原始事件保留天数、最大分区数、自动删除或关闭嵌入式原始事件层
- **THEN** 系统不把这些键作为受支持配置，也不因此删除或停止记录合规原始事件

### Requirement: SPEC-TOML-RAW-002 原始事件配置校验
原始目录 SHALL 接受可解析的本地路径；查询范围、页大小、磁盘阈值和投影批量大小 MUST 为正整数并受后端声明上限约束；阻断阈值 MUST 小于告警阈值；启动完整性策略 SHALL 只接受 `latest` 或 `all`。任一已知原始配置无效时，整次 raw TOML 保存 MUST 被拒绝且原文件保持不变。

#### Scenario: 磁盘阈值关系错误
- **WHEN** `aw.raw.lowDisk.blockBytes` 大于或等于 `aw.raw.lowDisk.warnBytes`
- **THEN** 配置保存返回带违规键的校验错误，磁盘上的 TOML 不变

#### Scenario: 无效完整性策略
- **WHEN** `aw.raw.integrity.verifyOnStartup` 不是 `latest` 或 `all`
- **THEN** 配置保存和启动配置解析拒绝该值，不静默关闭完整性校验

### Requirement: SPEC-TOML-RAW-003 原始目录变更边界
尚未产生原始事件时，系统 MAY 接受 `aw.raw.dir` 变更；已经存在任何原始分区后，普通配置保存 MUST 拒绝改变有效原始目录，并说明需要独立的显式转存流程。配置修改或路径不可用 MUST NOT 导致系统遗忘或自动删除旧分区。

#### Scenario: 有数据后修改原始目录
- **WHEN** 原始 catalog 已记录至少一个分区且用户修改 `aw.raw.dir`
- **THEN** 系统拒绝该配置变更，原配置和全部原始分区保持不变
