## MODIFIED Requirements

### Requirement: SPEC-AW-006 超时
连接与每个请求 SHALL 使用配置的 events.timeout，避免工具调用无限等待。

#### Scenario: 请求超过超时
- **WHEN** 事件服务 endpoint 未在配置时间内响应
- **THEN** 请求终止并返回结构化错误
