## MODIFIED Requirements

### Requirement: SPEC-WS-GOAL-001..005 可选外部信息能力
通过 MCP 管理启用并批准联网搜索服务时，Agent SHALL 能使用 MCP 搜索工具获取本地数据无法覆盖的实时外部信息。联网搜索 SHALL 默认不启用；MCP 管理页 SHALL 提供 Parallel Search 预设，仅填充公共端点和协议，不启用、联网或预填凭据。初始化失败 MUST NOT 阻止 Agent 启动。涉及用户个人活动、会话、Wiki 或文件元数据的问题，Agent SHOULD 优先使用相应本地工具，仅在需要外部事实时使用网络搜索。

#### Scenario: 搜索已启用且可用
- **WHEN** 用户在 MCP 管理页面添加并启用联网搜索服务、批准对应工具版本，并询问需要最新外部信息的问题
- **THEN** Agent MAY 调用已注册的联网搜索 MCP 工具，并基于结果回答

#### Scenario: 本地活动问题
- **WHEN** 用户询问自己的时间线、应用使用或历史任务
- **THEN** Agent 优先查询事件数据、Wiki 或其它本地工具，不把联网搜索当作个人事实来源

### Requirement: SPEC-WS-ARCH-001..005 MCP 工具集成
联网搜索 SHALL 复用外部 Streamable HTTP 连接的发现、版本批准和逐次调用确认，经批准的工具在新的受管 Agent 轮次使用现有串行工具链，免重启生效；撤权后不得依赖旧轮次的快照继续发送。客户端 SHALL 通过 MCP 协议完成 initialize/listTools 和工具 schema 发现，构建、初始化与请求 MUST 具有有限超时。应用层 MUST NOT 自实现搜索爬虫或复制远程工具 schema；兼容旧连接入口不再注册。

#### Scenario: MCP 初始化成功
- **WHEN** 联网搜索端点在超时内完成 initialize 和工具发现
- **THEN** 搜索工具按服务标识进入待批准目录，批准后才供新的受管 Agent 轮次使用

#### Scenario: 端点长时间无响应
- **WHEN** 连接或初始化超过配置的有限等待
- **THEN** 初始化失败并降级为该服务错误状态，Agent 与其他健康服务继续运行

### Requirement: SPEC-WS-CFG-001..006 配置与重启
`config.toml` 中的旧配置键 `websearch.enabled`、`websearch.mcp-url` 与 `websearch.api-key` SHALL 从活跃配置移除并作为退役键忽略；`WEBSEARCH_ENABLED`、`WEBSEARCH_MCP_URL` 与 `WEBSEARCH_API_KEY` 环境变量 MUST NOT 生效。系统 MUST NOT 自动迁移旧开关、地址、凭据或工具批准。已存在的合法 TOML 原文可保留，不进入模板、结构化读取、有效配置或 Agent 工具，不产生未知键或重启提示；结构化保存忽略旧字段，Agent 写入拒绝。联网搜索的启用状态和地址（预设默认 `https://search.parallel.ai/mcp`）仅由现有连接配置管理。匿名模式不附带 Authorization；Bearer 模式只使用用户为该目标显式提供且已绑定的凭据，缺失时不得自动改为匿名或继承其他密钥。未明确配置时不得创建该搜索连接或尝试联网。

#### Scenario: 默认配置
- **WHEN** 用户没有添加联网搜索服务，而旧 TOML、旧环境变量或已有连接文件中的历史 `legacy-websearch` 批准仍在
- **THEN** 系统不创建、显示、发现或调用兼容搜索连接，不复制地址、凭据或批准；本地能力继续可用

#### Scenario: 保留旧 TOML
- **WHEN** 用户保存包含三个退役键的合法 TOML，随后通过结构化配置提交旧字段
- **THEN** 原文保存不报告这些退役键为未知或需重启，结构化保存不覆盖旧值，也不返回旧凭据

#### Scenario: 匿名端点
- **WHEN** 用户明确将预设添加为匿名连接，并执行授权范围内的探测或调用
- **THEN** client 不发送 Authorization header

#### Scenario: Bearer token
- **WHEN** 用户为联网搜索 MCP 服务的当前目标明确配置 Bearer，并执行授权范围内的探测或调用
- **THEN** MCP 请求使用该目标绑定的 Bearer，日志和错误不输出 key；更换目标需重新配置凭据

### Requirement: SPEC-WS-UI-001..005 配置入口
用户 SHALL 通过桌面端顶栏 `MCP` 页面配置、测试、启停与删除联网搜索连接。保存或启停无需重启；保存成功 MUST NOT 视为远端可用、工具已批准或有效调用授权。凭据字段遵循敏感输入语义及既有会话或系统安全存储规则；不提供旧 TOML 搜索定位或管理入口。

#### Scenario: 修改联网搜索配置
- **WHEN** 用户在顶栏 `MCP` Tab 页通过联网搜索预设添加或编辑服务并保存启用
- **THEN** 后端校验并持久化连接配置，按现有机制发现及批准工具；无需重启，不因保存自动授予工具或调用权限

### Requirement: SPEC-WS-PRV-001..003 搜索隐私
日志、状态响应和错误 MUST NOT 输出联网搜索 API key。联网搜索凭据使用外部 MCP 的目标绑定存储与敏感输入语义。联网 client SHALL 只发送模型显式提供且经用户逐次批准的工具参数，不自动附加屏幕原文、无障碍树、配置或本地数据库内容；Agent prompt SHALL 指示仅发送回答外部问题所需的用户问题或关键词。

#### Scenario: 搜索外部技术问题
- **WHEN** 用户确认 Agent 搜索调用的目标和具体参数
- **THEN** 工具请求只包含批准的查询参数；凭据仅作为已绑定目标的认证头，不加入工具参数

### Requirement: SPEC-WS-NON-001..005 功能边界
联网搜索 MUST NOT 自建爬虫/索引、缓存搜索结果、增加独立结果面板或实现 OAuth。用户 MAY 通过独立配置添加多个外部搜索连接；每个连接 MUST 遵循既有公网 HTTPS、DNS、TLS、工具版本批准和逐次调用确认边界。旧 `legacy-websearch` 身份不能用于创建新连接或替换已有配置。

#### Scenario: 多服务商切换
- **WHEN** 用户添加另一个搜索端点
- **THEN** 独立配置并核对新接收方；不复用旧配置的认证信息或工具批准

#### Scenario: 升级后旧地址不符合安全边界
- **WHEN** 旧 TOML 残留明文、私网或其它不适用的搜索地址
- **THEN** 仅允许保留合法 TOML 原文，既不创建连接、尝试改址或发送请求，也不复制凭据
