## MODIFIED Requirements

### Requirement: SPEC-AGT-003 工具注册与顺序执行
Agent SHALL 注册 EventQueryTools，并在相应依赖可用时注册 ConfigTools、WikiTools、FileTools、已批准的联网搜索/外部 MCP 及已授权的内置邮箱工具。工具执行 SHALL 使用串行 toolkit，避免共享本地 store/connection 的并发访问；外部工具和邮箱调用 SHALL 经过后端权限网关，不直接绕过批准与账户范围检查。可选工具初始化失败 MUST NOT 阻止其余 Agent 能力启动；失败客户端 SHALL 被关闭或跳过。联网搜索 SHALL 只从显式配置且批准的外部 MCP 连接取得工具，不注册旧 `websearch.*` 兼容入口。

#### Scenario: 可选 Wiki/File/Config 工具不可用
- **WHEN** 某个可选 store 或配置入口未创建
- **THEN** Agent 不注册对应工具，其它已满足依赖的工具仍可使用

#### Scenario: 联网搜索初始化失败
- **WHEN** 用户显式启用的外部搜索 MCP 连接初始化或发现失败
- **THEN** Agent 跳过联网搜索并继续使用本地工具

#### Scenario: 只存在已退役的搜索配置
- **WHEN** 旧 TOML 或环境中仍有搜索设置，但没有新的外部搜索 MCP 连接
- **THEN** Agent 不注册搜索工具，不向旧地址发送请求
