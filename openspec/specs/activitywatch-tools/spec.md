# ActivityWatch Agent 工具规格

## Purpose

定义 Agent 通过本地兼容 HTTP API 列出事件桶、查询投影事件、执行 AQL 和读取服务器信息的请求、预算与错误降级行为。

## Requirements

### Requirement: SPEC-AW-001 base URL 与路径
工具 SHALL 规范化构造器 baseUrl 的尾部斜杠，并把所有 API 路径直接追加到已经包含 `/api/0` 的 base URL，
不得重复添加版本前缀。

#### Scenario: baseUrl 带尾斜杠
- **WHEN** 构造 baseUrl 以 `/` 结尾
- **THEN** 请求 URL 只包含一个路径分隔符和一个 `/api/0` 前缀

### Requirement: SPEC-AW-002 工具方法
Agent SHALL 能调用 listBuckets、queryEvents、executeAQL、getServerInfo 和 getSettings；每个工具 SHALL
使用对应 ActivityWatch GET/POST endpoint，并返回 JSON 字符串结果。

#### Scenario: 列出 bucket
- **WHEN** Agent 调用 listBuckets
- **THEN** 工具请求 AW buckets endpoint 并返回服务端 JSON

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

#### Scenario: AW 不可达
- **WHEN** 本地 AW 请求连接失败或超时
- **THEN** 工具返回 error JSON，Agent 仍可使用其它工具

### Requirement: SPEC-AW-006 超时
连接与每个请求 SHALL 使用配置的 aw.timeout，避免工具调用无限等待。

#### Scenario: 请求超过超时
- **WHEN** AW endpoint 未在配置时间内响应
- **THEN** 请求终止并返回结构化错误

### Requirement: SPEC-AW-007 Agent 工具可发现性
所有向 Agent 暴露的方法 SHALL 注册为工具，参数 SHALL 具有名称和用途描述，使模型能构造合法调用。
内部辅助方法和构造器 MUST NOT 作为 Agent 工具暴露。向 Agent 注册的实现类型 SHALL 为 `EventQueryTools`。
工具描述 SHALL 使用事件桶与投影查询用语，MUST NOT 把本产品工具描述为 ActivityWatch 客户端；底层 HTTP 路径仍为配置的 `/api/0` base URL。

#### Scenario: Toolkit 注册
- **WHEN** SelfAnalystAgent 注册 EventQueryTools
- **THEN** 模型只看到五个公开工具及其参数 schema
