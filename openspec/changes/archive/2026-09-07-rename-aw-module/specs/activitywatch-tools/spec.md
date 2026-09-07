## MODIFIED Requirements

### Requirement: SPEC-AW-007 Agent 工具可发现性
所有向 Agent 暴露的方法 SHALL 注册为工具，参数 SHALL 具有名称和用途描述，使模型能构造合法调用。
内部辅助方法和构造器 MUST NOT 作为 Agent 工具暴露。向 Agent 注册的实现类型 SHALL 为 `EventQueryTools`。
工具描述 SHALL 使用事件桶与投影查询用语，MUST NOT 把本产品工具描述为 ActivityWatch 客户端；底层 HTTP 路径仍为配置的 `/api/0` base URL。

#### Scenario: Toolkit 注册
- **WHEN** SelfAnalystAgent 注册 EventQueryTools
- **THEN** 模型只看到五个公开工具及其参数 schema
