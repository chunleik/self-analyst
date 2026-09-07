## REMOVED Requirements

### Requirement: SPEC-RAW-010 派生投影可重建
**Reason**: 场景名「ActivityWatch 投影损坏」含品牌名，指代的却是本产品自研投影；OpenSpec 无法单独
重命名场景，故整体替换为同 ID 的新条款。行为契约不变。
**Migration**: 见本 capability 中「SPEC-RAW-010 派生事件投影可重建」，SPEC ID 与约束原样承接。

## ADDED Requirements

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
- **THEN** 当前有效投影文件为 `{aw.data-dir}/events.db`，而不是 `aw.db`

## MODIFIED Requirements

### Requirement: SPEC-RAW-004 原始优先写入与可恢复投影
系统 MUST 在更新任何事件投影前提交原始事件。原始提交失败时 MUST NOT 产生 projection-only 事件；原始提交成功而投影失败时 SHALL 保留原始事件、把投影标记为待处理并允许后台重试。HTTP 响应 SHALL 区分完全成功、原始已接收但投影待处理以及原始写入失败。

#### Scenario: 投影写入失败
- **WHEN** 原始事件事务成功但事件投影写入失败
- **THEN** 系统保留原始事件并返回原始已接收、投影待处理的成功接收状态，后台随后能够补齐投影

#### Scenario: 原始写入失败
- **WHEN** 原始事件无法完成持久化事务
- **THEN** 系统返回失败，不更新事件投影，并把对应 collector 状态置为 degraded 或 blocked

### Requirement: SPEC-RAW-006 原始事件桌面查询
系统 SHALL 仅通过受保护的桌面 API 提供原始事件查询。查询 MUST 要求 bucket、开始时间和结束时间，使用稳定游标分页并限制时间范围和单页数量；响应 SHALL 返回事件字段、下一页游标和覆盖信息。现有事件查询、AQL、WikiTools 和 Agent 工具 MUST 继续查询派生投影，且 MUST NOT 暴露逐 heartbeat 原始查询能力。

#### Scenario: 有界原始查询
- **WHEN** 已认证桌面客户端使用合法 bucket、时间范围、limit 和 cursor 查询原始事件
- **THEN** 系统按接收时间与事件 ID 的稳定顺序返回一页结果和可选下一页游标，无遗漏或重复

#### Scenario: 禁止无界查询
- **WHEN** 原始查询缺少 bucket 或时间边界，或请求范围和页大小超过配置上限
- **THEN** 系统返回参数错误且不扫描全部永久历史

#### Scenario: AQL 仍查询投影
- **WHEN** 用户通过 AQL 或事件查询 Agent 工具查询同一 bucket
- **THEN** 返回 heartbeat 合并后的事件投影，而不是逐条原始事件

### Requirement: SPEC-RAW-008 嵌入式模式边界
永久原始事件保证 SHALL 仅在应用控制采集写入链路和数据目录的嵌入式事件服务模式启用。外部 ActivityWatch 模式 MUST 明确报告原始事件能力不可用，MUST NOT 声称外部服务中的事件已被永久原始层保存。

#### Scenario: 外部 ActivityWatch 模式
- **WHEN** 应用以外部 ActivityWatch 模式启动
- **THEN** 原始存储和查询状态报告 unavailable，原始查询不代理或伪造外部服务数据
