## MODIFIED Requirements

### Requirement: SPEC-WS-GOAL-001..005 可选外部信息能力
通过 MCP 管理平台启用并成功注册联网搜索服务时，Agent SHALL 能使用 MCP 搜索工具获取本地数据无法覆盖的实时外部信息。联网搜索 SHALL 默认不启用，并作为 MCP 管理面板中的预设服务模板提供（支持自定义端点与可选 API key）。初始化失败 MUST NOT 阻止 Agent 启动。涉及用户个人活动、会话、Wiki 或文件元数据的问题，Agent SHOULD 优先使用相应本地工具，仅在需要外部事实时使用网络搜索。

#### Scenario: 搜索已启用且可用
- **WHEN** 用户在 MCP 管理页面添加并启用联网搜索服务，并询问需要最新外部信息的问题
- **THEN** Agent MAY 调用已注册的联网搜索 MCP 工具，并基于结果回答

#### Scenario: 本地活动问题
- **WHEN** 用户询问自己的时间线、应用使用或历史任务
- **THEN** Agent 优先查询事件数据、Wiki 或其它本地工具，不把联网搜索当作个人事实来源

### Requirement: SPEC-WS-ARCH-001..005 MCP 工具集成
联网搜索 SHALL 通过通用 MCP 管理平台的外部 Streamable HTTP Provider 注册到 Agent 的同一串行 Toolkit，免重启热生效。客户端 SHALL 通过 SDK 完成 initialize/listTools 和工具 schema 注册，构建、初始化与注册阶段 MUST 具有有限超时，不能无限阻塞 Agent 构造或运行。应用层 MUST NOT 自实现搜索爬虫或复制远程工具 schema。

#### Scenario: MCP 初始化成功
- **WHEN** 联网搜索端点在超时内完成 initialize 和工具发现
- **THEN** 搜索工具按服务标识加入 Agent Toolkit，与本地工具使用同一调用链

#### Scenario: 端点长时间无响应
- **WHEN** 连接或初始化超过配置的有限等待
- **THEN** 初始化失败并降级为该服务错误状态，Agent 与其他健康服务继续运行

### Requirement: SPEC-WS-CFG-001..006 配置与重启
`config.toml` 中的旧配置键 `websearch.enabled`、`websearch.mcp-url` 与 `websearch.api-key` SHALL 从活跃配置白名单移除并移入退役键列表，不再作为联网搜索配置来源，也不提供自动向 MCP 注册表的迁移。联网搜索的启用状态、端点地址（预设默认 `https://search.parallel.ai/mcp`）和可选 Bearer Token SHALL 统一由 MCP 管理注册表与受限凭据存储管理，并支持免重启热生效。未添加或未启用联网搜索 MCP 服务时，系统 MUST 不创建搜索 MCP client、不得发起搜索网络请求；非空 API key SHALL 作为 `Authorization: Bearer <key>` 发送，空 key MUST 不发送该 header。

#### Scenario: 默认配置
- **WHEN** 用户未在 MCP 管理页面添加或启用联网搜索服务（即使旧 `config.toml` 残留 `websearch.*` 退役键）
- **THEN** 系统静默忽略旧退役键，Agent 不连接远程搜索 MCP，全部本地能力继续可用

#### Scenario: 匿名端点
- **WHEN** 用户通过联网搜索预设添加服务，开启对内启用、端点 URL 有效且 API key 为空
- **THEN** client 热连接端点但不发送 Authorization header

#### Scenario: Bearer token
- **WHEN** 用户在联网搜索 MCP 服务中配置非空 API key 并启用
- **THEN** MCP 请求使用 Bearer header，日志和错误不输出 key

### Requirement: SPEC-WS-UI-001..005 配置入口
用户 SHALL 通过桌面端顶栏独立 `MCP` Tab 页的添加服务向导（联网搜索预设）及服务卡片配置、测试、启停与删除联网搜索 MCP 服务。保存或启停变更 SHALL 即时热生效，无需重启后端；凭据字段在界面中遵循敏感输入与脱敏显示语义。

#### Scenario: 修改联网搜索配置
- **WHEN** 用户在顶栏 `MCP` Tab 页通过联网搜索预设添加或编辑服务并保存启用
- **THEN** 后端校验并持久化至 MCP 注册表与受限凭据存储，热注册到 Agent Toolkit 且无需重启后端

### Requirement: SPEC-WS-PRV-001..003 搜索隐私
日志、状态响应和错误 MUST NOT 输出联网搜索 API key。联网搜索凭据 SHALL 存放在受限 MCP 凭据存储中并在 UI 中采用敏感输入语义。联网 client SHALL 只发送模型显式提供的工具参数，不自动附加屏幕原文、无障碍树、配置或本地数据库内容；Agent prompt SHALL 指示仅发送回答外部问题所需的用户问题或关键词。

#### Scenario: 搜索外部技术问题
- **WHEN** Agent 调用联网搜索 MCP 工具
- **THEN** 请求只包含该工具调用的查询参数，不由 client 自动附加本地活动上下文或密钥

## REMOVED Requirements

### Requirement: SPEC-WS-NON-001..005 功能边界
**Reason**: 联网搜索已纳入统一 MCP 管理平台，支持与其他多个 MCP 服务并存并在运行期热启停或配置多个搜索端点，原“仅支持单一硬编码端点且重启生效”的边界不再适用。
**Migration**: 通过桌面端 `MCP` Tab 页添加、切换或管理一个或多个外部搜索 MCP 服务；旧 `config.toml` 中的 `websearch.*` 键已退役。
