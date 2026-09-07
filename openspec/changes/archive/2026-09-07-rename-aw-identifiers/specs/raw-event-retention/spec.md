## MODIFIED Requirements

### Requirement: SPEC-RAW-010 派生投影可重建
ActivityWatch 事件、Wiki 数据和语义索引 SHALL 被视为可重建派生数据。嵌入式投影库文件 SHALL 名为 `events.db`。
ActivityWatch 投影 SHALL 记录投影版本和连续原始覆盖位置；重建 SHALL 写入独立目标、验证覆盖与一致性，并在成功后原子切换，MUST NOT 就地修改原始分区。重建旁路与备份文件 SHALL 使用 `events.db.rebuilding-*` 与 `events.db.backup-*` 前缀，MUST NOT 再创建 `aw.db` 作为当前投影。

#### Scenario: ActivityWatch 投影损坏
- **WHEN** 用户请求从健康原始分区重建 ActivityWatch 投影
- **THEN** 系统按稳定原始顺序生成并验证新投影，成功切换后查询恢复且原始分区未改变

#### Scenario: 重建中断
- **WHEN** 投影重建在完成验证和切换前中断
- **THEN** 当前有效投影和全部原始事件保持可用，未完成目标可在后续安全重试或作为派生文件清理

#### Scenario: 当前投影文件名
- **WHEN** 嵌入式模式成功初始化或完成投影重建
- **THEN** 当前有效投影文件为 `{aw.data-dir}/events.db`，而不是 `aw.db`

### Requirement: SPEC-RAW-011 开发阶段启用边界
系统 SHALL 从永久原始事件能力正式启用后的第一条合规事件开始建立事实源，MUST NOT 自动扫描、导入、修改或删除启用前的开发期 `aw.db`、`events.db` 或旧 bucket 数据库，也 MUST NOT 为这些开发数据增加兼容读取或精度标记。当前版本不提供把旧 `aw.db` 或旧 `aw-watcher-*` 桶改写为新文件名/新 ID 的迁移。

#### Scenario: 首次启用原始事件层
- **WHEN** 开发环境第一次以新原始事件能力启动且目录中存在旧开发数据库
- **THEN** 新原始层从当前新事件开始记录，不导入、不转换且不自动删除旧开发数据库

#### Scenario: 目录中残留 aw.db
- **WHEN** 数据目录同时存在历史 `aw.db` 与当前 `events.db`
- **THEN** 运行时只把 `events.db` 当作当前投影，不把 `aw.db` 中的事件合并进新库
