## MODIFIED Requirements

### Requirement: SPEC-CTP-010 内容 bucket 写入策略适用范围
系统 SHALL 对 ID 以 `watcher-content_` 或 `aw-watcher-content_` 开头，或已存 bucket 的 `client` 大小写不敏感等于
`watcher-content` 或 `aw-watcher-content` 的所有应用写入执行内容事件 v2 策略；其他 bucket 的既有通用字段 SHALL 不受
此策略影响。本产品内容采集器新写入 SHALL 使用 `watcher-content_{hostname}` 与 client `watcher-content`。

#### Scenario: bucket ID 前缀触发策略
- **WHEN** 禁止字段写入 ID 以 `watcher-content_` 或 `aw-watcher-content_` 开头的 bucket
- **THEN** 写入被内容事件策略拒绝

#### Scenario: bucket client 触发策略
- **WHEN** 禁止字段写入自定义 ID、但 client 为 `watcher-content` 或 `aw-watcher-content` 的已存 bucket
- **THEN** 写入被内容事件策略拒绝

#### Scenario: 非内容 bucket 不受影响
- **WHEN** 同一通用字段写入既没有内容前缀也没有内容 client 的 bucket
- **THEN** 写入不因内容事件 v2 策略而失败

### Requirement: SPEC-CTP-030 增量逻辑净化与状态记录
嵌入式启动迁移 SHALL 扫描 bucket ID 具有内容前缀 `watcher-content_` 或 `aw-watcher-content_`，或 bucket client 精确等于规范值
`watcher-content` 或 `aw-watcher-content` 的内容行，并将其规范化为 v2 投影，删除正文和未知字段。首次运行 SHALL 扫描
该选择范围内全部内容行；已有完成记录时 SHALL 从已完成高水位之后继续扫描，并把成功扫描水位推进
到当前全局最大事件 ID。发生需物理净化的逻辑修改时 SHALL 先记录 pending 状态。写策略对 client
大小写不敏感的更宽识别范围，不构成历史迁移对混合大小写 client bucket 的净化保证。

#### Scenario: 首次净化提取可靠旧标题
- **WHEN** 旧微信内容事件含正文和可可靠识别的聊天标题
- **THEN** 迁移保留标题投影并删除正文及未知字段

#### Scenario: 后续运行只处理新高水位
- **WHEN** 初次迁移完成后又出现 ID 更高的旧内容行
- **THEN** 后续迁移处理该新行，再次运行不重复扫描已完成范围

#### Scenario: 非内容长尾推进高水位
- **WHEN** 内容行之后存在 ID 更高的非内容事件
- **THEN** 成功扫描水位推进到全局最大事件 ID，避免后续重复扫描同一长尾

#### Scenario: 混合大小写的自定义 content client
- **WHEN** 自定义 bucket 的 client 仅以大小写变体匹配 `watcher-content` 或 `aw-watcher-content`，且 bucket ID 没有内容前缀
- **THEN** 新写入仍受内容策略保护，但当前历史迁移不保证扫描或净化该 bucket 的既有行

## ADDED Requirements

### Requirement: SPEC-CTP-042 默认桶列表隐藏内容采集桶
默认 bucket 列表 SHALL 隐藏 ID 以 `watcher-content_` 或 `aw-watcher-content_` 开头的桶；完整列表或显式查询仍可返回这些桶。

#### Scenario: 默认列表不展示内容桶
- **WHEN** 客户端请求默认 bucket 列表且存在本产品内容采集桶
- **THEN** 响应不包含该内容桶 ID，窗口等其他采集桶仍可见
