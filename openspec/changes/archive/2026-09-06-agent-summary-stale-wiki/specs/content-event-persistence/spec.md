## MODIFIED Requirements

### Requirement: SPEC-CTP-041 桌面状态与时间线响应边界
桌面状态响应的 `contentPersistence` 子对象 SHALL 返回 `schemaVersion`、`ready` 和 `status`，并只在
迁移失败时返回经单行和长度限制处理的 `error`；完整状态响应 MAY 同时包含 backend、language、AW、
collectors 和 LLM 等其他状态。迁移失败时 `contentPersistence.status` SHALL 为 `migration_failed`，
上下文标题 collector SHALL 为 `degraded`。桌面 summary/timeline SHALL 从 window/AFK 事实，或由这些
允许事实派生的 Wiki 摘要构造响应，MUST NOT 读取或拼接无障碍正文。通用 AW events、AQL 和 export API
返回持久化事件 data，不提供额外字段投影。

#### Scenario: 桌面迁移失败状态不返回事件
- **WHEN** 内容迁移失败且客户端请求桌面状态
- **THEN** 响应报告 `degraded` 和 `migration_failed`，但不包含内容事件或无障碍正文

#### Scenario: 桌面摘要只使用窗口与 AFK 事实
- **WHEN** 客户端请求当前摘要和时间线
- **THEN** 响应由窗口标题、应用、耗时、AFK 数据或由其派生的 Wiki 摘要构造，不拼接隐藏控件文本
