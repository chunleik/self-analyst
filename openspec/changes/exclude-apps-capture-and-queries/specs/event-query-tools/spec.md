## ADDED Requirements

### Requirement: SPEC-AW-008 查询结果隐私过滤
`queryEvents` 和 `executeAQL` 在把事件服务的响应返回给 Agent 之前 SHALL 应用对模型隐藏的隐私策略：响应中任意层级数组内、`data` 字段命中排除应用（`wiki.privacy.excludeApps` 与 `events.collection.excludeApps` 的并集，按可执行文件名完整匹配、不区分大小写）、排除网站（`wiki.privacy.excludeSites`，按 `title`、`context_title` 关键词或 `url_host` 匹配）、无痕标题标记或 `private_browsing=true` 的事件对象 MUST 被移除。其余事件、非事件值和响应结构 SHALL 保持不变；过滤 SHALL 在 80000 字符预算截断之前完成。响应无法解析为 JSON 时 MUST NOT 原样返回可能含排除数据的内容，SHALL 返回结构化 error JSON。每次工具调用 SHALL 使用调用时最新保存的隐私配置，配置变更 MUST NOT 需要重启后端或新建会话；已经返回给模型的历史工具结果不追溯修改。服务端已经聚合成数值的结果 MAY 包含被隐藏事件的时长，但 MUST NOT 暴露其应用名或标题。

#### Scenario: 查询窗口事件
- **WHEN** 排除应用包含 `PotPlayerMini64.exe`，Agent 调用 queryEvents 查询包含该应用事件的时间段
- **THEN** 返回的事件数组不含 `data.app` 为该应用的事件，其他应用事件原样保留

#### Scenario: AQL 合并结果
- **WHEN** Agent 执行 AQL 按 app 合并事件并返回排序列表
- **THEN** 结果列表不含排除应用的条目，其他条目的字段与顺序不变

#### Scenario: 无排除配置
- **WHEN** 两个排除列表和排除网站均为空，且事件不含无痕标记
- **THEN** 工具返回的 JSON 与事件服务响应语义一致

#### Scenario: 会话中修改隐私配置
- **WHEN** 同一会话中 Agent 先查询一次，用户随后把 `weixin.exe` 设为“仅对模型隐藏”并保存，Agent 再次查询同一时间段
- **THEN** 第二次结果不含 `weixin.exe` 事件，第一次结果在会话历史中保持不变
