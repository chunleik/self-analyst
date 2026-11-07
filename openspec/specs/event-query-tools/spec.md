# event-query-tools Specification

## Purpose
定义 Agent 通过本地兼容 HTTP API 列出事件桶、查询投影事件、执行 AQL 和读取服务信息的请求、预算与
错误降级行为。本 capability 由 `activitywatch-tools` 重命名而来，与实现类型 `EventQueryTools` 对齐。

## Requirements

### Requirement: SPEC-AW-001 base URL 与路径
工具 SHALL 规范化构造器 baseUrl 的尾部斜杠，并把所有 API 路径直接追加到已经包含 `/api/0` 的 base URL，
不得重复添加版本前缀。

#### Scenario: baseUrl 带尾斜杠
- **WHEN** 构造 baseUrl 以 `/` 结尾
- **THEN** 请求 URL 只包含一个路径分隔符和一个 `/api/0` 前缀

### Requirement: SPEC-AW-002 工具方法
Agent SHALL 能调用 listBuckets、queryEvents、executeAQL、getServerInfo 和 getSettings；每个工具 SHALL
使用兼容 API 中对应的 GET/POST endpoint，并返回 JSON 字符串结果。

#### Scenario: 列出 bucket
- **WHEN** Agent 调用 listBuckets
- **THEN** 工具请求兼容 API 的 buckets endpoint 并返回服务端 JSON

### Requirement: SPEC-AW-003 事件查询参数
queryEvents SHALL 始终发送 limit，并将 limit 限制为最多 500；非空 start/end SHALL 作为 URL query
参数透传。工具不负责验证 ISO 时间格式。最终序列化结果 SHALL 受 80000 字符预算限制。

#### Scenario: 有界事件查询
- **WHEN** Agent 请求 limit=1000 并提供 start/end
- **THEN** HTTP 请求最多查询 500 条并包含透传时间参数，返回结果不超过工具预算

### Requirement: SPEC-AW-004 AQL 请求
executeAQL SHALL 把输入按分号拆分，去除空片段，并为每条 query 恢复结尾分号；请求体 SHALL 包含
timeperiods 数组和 query 数组，使用 application/json，并正确 JSON 转义双引号。

#### Scenario: 多行 AQL
- **WHEN** 输入包含多个以分号分隔的非空语句
- **THEN** 请求 JSON 的 query 数组逐条包含规范化语句和结尾分号

### Requirement: SPEC-AW-005 错误降级
HTTP、IO 或中断错误 SHALL 转换为结构化 error JSON，而不是向 Agent 抛出未处理异常。InterruptedException
处理 SHALL 恢复线程中断状态。错误结果 MUST NOT 包含配置密钥或无界响应体。

#### Scenario: 事件服务不可达
- **WHEN** 本地事件服务请求连接失败或超时
- **THEN** 工具返回 error JSON，Agent 仍可使用其它工具

### Requirement: SPEC-AW-006 超时
连接与每个请求 SHALL 使用配置的 events.timeout，避免工具调用无限等待。

#### Scenario: 请求超过超时
- **WHEN** 事件服务 endpoint 未在配置时间内响应
- **THEN** 请求终止并返回结构化错误

### Requirement: SPEC-AW-007 Agent 工具可发现性
所有向 Agent 暴露的方法 SHALL 注册为工具，参数 SHALL 具有名称和用途描述，使模型能构造合法调用。
内部辅助方法和构造器 MUST NOT 作为 Agent 工具暴露。向 Agent 注册的实现类型 SHALL 为 `EventQueryTools`。
工具描述 SHALL 使用事件桶与投影查询用语，MUST NOT 把本产品工具描述为 ActivityWatch 客户端；底层 HTTP 路径仍为配置的 `/api/0` base URL。

#### Scenario: Toolkit 注册
- **WHEN** SelfAnalystAgent 注册 EventQueryTools
- **THEN** 模型只看到五个公开工具及其参数 schema

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
