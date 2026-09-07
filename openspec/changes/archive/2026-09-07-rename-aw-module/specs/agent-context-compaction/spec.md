## MODIFIED Requirements

### Requirement: SPEC-ACOMP-040 工具结果上下文预算
事件查询工具 `queryEvents` 的 limit MUST 不超过 500；任一事件查询工具结果 MUST 限制为最多
80000 字符，以防单次工具结果撑满当前 reasoning 上下文。超限结果 SHALL 截断或拒绝，而不得无界注入。

#### Scenario: 过大事件查询
- **WHEN** Agent 请求超过 500 条投影事件
- **THEN** 工具将实际查询限制在 500 条以内

#### Scenario: 过大工具结果
- **WHEN** 事件查询工具序列化结果超过 80000 字符
- **THEN** 返回给 Agent 的结果被限制在预算内
