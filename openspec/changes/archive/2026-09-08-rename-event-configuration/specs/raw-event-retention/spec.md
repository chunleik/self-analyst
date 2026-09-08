## ADDED Requirements

### Requirement: SPEC-RAW-013 按配置执行启动完整性检查

嵌入式事件服务 SHALL 在恢复中断封存之后、投影恢复及监听/采集启动之前，按 `events.raw.integrity.startupScope` 执行检查。latest SHALL 选择 catalog 中月份最新的一个分区；all SHALL 按月份检查全部已有分区，空 catalog SHALL 不执行分区检查。外部模式 MUST NOT 扫描本地原始分区。

检查 SHALL 只读验证 SQLite 完整性及 catalog 记录的事件数量、事件 ID 和接收时间边界；封存分区 SHALL 额外核对 manifest 的版本、月份、文件名、统计信息、文件大小与 SHA-256，并与 catalog 的封存摘要一致。选中分区不存在、不可安全读取、已隔离或任一检查失败时，系统 SHALL 在 catalog 标记隔离并阻止本次应用启动，MUST NOT 发布桌面端口、启动采集或以默认目录继续。错误 SHALL 仅包含月份及可操作的失败说明，不包含原始 payload、文件路径或底层异常详情。

成功检查 SHALL 更新 catalog 中该分区的最近校验时间；检查 MUST NOT 改写、补造或删除分区文件及 manifest，也 MUST NOT 自动修复或重建数据。

#### Scenario: 最新分区检查范围
- **WHEN** 存在多个已登记分区且 startupScope 为 latest
- **THEN** 只检查月份最新的分区并更新其最近校验时间，不因未选中的旧 manifest 检查失败而阻止启动

#### Scenario: 全部分区检查发现旧封存损坏
- **WHEN** startupScope 为 all，旧封存分区 manifest 与实际文件摘要不一致
- **THEN** 对应分区被标记隔离，应用启动失败且不发布端口，分区与 manifest 原文件保持不变

#### Scenario: 最新活动分区不一致
- **WHEN** 被选中的活动分区与 catalog 的事件数量或边界不一致
- **THEN** 应用启动失败并报告对应月份，不修正 catalog 统计以掩盖不一致，不删除原始事件

#### Scenario: 空目录首次启动
- **WHEN** 合法新配置指向尚无原始分区的目录
- **THEN** 不因缺少分区或 manifest 而失败，按原有首次启动流程建立存储

## MODIFIED Requirements

### Requirement: SPEC-RAW-010 派生事件投影可重建
事件投影、Wiki 数据和语义索引 SHALL 被视为可重建派生数据。嵌入式投影库文件 SHALL 名为 `events.db`。
事件投影 SHALL 记录投影版本和连续原始覆盖位置；重建 SHALL 写入独立目标、验证覆盖与一致性，并在成功后原子切换，MUST NOT 就地修改原始分区。重建旁路与备份文件 SHALL 使用 `events.db.rebuilding-*` 与 `events.db.backup-*` 前缀，MUST NOT 再创建 `aw.db` 作为当前投影。

#### Scenario: 事件投影损坏
- **WHEN** 用户请求从健康原始分区重建事件投影
- **THEN** 系统按稳定原始顺序生成并验证新投影，成功切换后查询恢复且原始分区未改变

#### Scenario: 重建中断
- **WHEN** 投影重建在完成验证和切换前中断
- **THEN** 当前有效投影和全部原始事件保持可用，未完成目标可在后续安全重试或作为派生文件清理

#### Scenario: 当前投影文件名
- **WHEN** 嵌入式模式成功初始化或完成投影重建
- **THEN** 当前有效投影文件为 `{events.data-dir}/events.db`，而不是 `aw.db`

#### Scenario: 配置改名保留现有存储
- **WHEN** 用户按配置对照表手动更名并保持有效数据目录和原始目录不变
- **THEN** 系统继续使用已有 `events.db` 与原始分区，不因键名变化触发迁移、导入、删除或投影重建
