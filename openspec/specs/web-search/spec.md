# Agent 联网搜索规格

## Purpose

定义 Agent 通过单一 Streamable HTTP MCP 端点获得外部实时信息的配置、注册、失败降级、工具选择与隐私边界；联网能力默认关闭。

## Requirements

### Requirement: SPEC-WS-GOAL-001..005 可选外部信息能力
启用并成功注册时，Agent SHALL 能使用 MCP 搜索工具获取本地数据无法覆盖的实时外部信息。联网搜索
SHALL 默认关闭，并支持配置开关、单一端点和可选 API key。初始化失败 MUST NOT 阻止 Agent 启动。
涉及用户个人活动、会话、Wiki 或文件元数据的问题，Agent SHOULD 优先使用相应本地工具，仅在需要
外部事实时使用网络搜索。

#### Scenario: 搜索已启用且可用
- **WHEN** 用户询问需要最新外部信息的问题
- **THEN** Agent MAY 调用已注册 MCP 搜索工具，并基于结果回答

#### Scenario: 本地活动问题
- **WHEN** 用户询问自己的时间线、应用使用或历史任务
- **THEN** Agent 优先查询事件数据、Wiki 或其它本地工具，不把联网搜索当作个人事实来源

### Requirement: SPEC-WS-ARCH-001..005 MCP 工具集成
联网搜索 SHALL 以 MCP client 接入统一连接管理，并通过受控工具适配注册到 Agent 的同一串行 Toolkit，使用 Streamable HTTP 端点。客户端 SHALL 通过 SDK 完成 initialize/listTools 和工具 schema 发现，构建、初始化与注册阶段 MUST 具有有限超时，不能无限阻塞 Agent 构造。应用层 MUST NOT 自实现搜索爬虫或复制远程工具 schema。同一兼容入口 MUST NOT 被旧逻辑和新管理器重复注册。

#### Scenario: MCP 初始化成功
- **WHEN** 端点在超时内完成 initialize 和工具发现
- **THEN** 工具进入待批准目录，经批准后加入受控 Agent Toolkit，与本地工具使用同一串行调用链

#### Scenario: 端点长时间无响应
- **WHEN** 连接或初始化超过配置的有限等待
- **THEN** 初始化失败并降级，Agent 构造继续

### Requirement: SPEC-WS-CFG-001..006 配置与重启
`websearch.enabled` SHALL 默认 false；`websearch.mcp-url` SHALL 默认 `https://search.parallel.ai/mcp`；`websearch.api-key` SHALL 默认为空。enabled=false 或 URL 为空时 MUST 不创建 MCP client、不得发起搜索网络请求。非空 key SHALL 作为目标绑定的 Bearer 发送，空 key 不发送该 header。三个键 SHALL 保留 TOML、结构化兼容端点及 ConfigTools 入口和 restartRequired；Agent 对这些键的修改 SHALL 先取得受管桌面用户对目标/变更的确认，不能自行扩大权限。旧秘密 MUST NOT 自动复制到新连接配置。

#### Scenario: 默认配置
- **WHEN** 用户没有启用 websearch
- **THEN** Agent 不连接远程 MCP，全部本地能力继续可用

#### Scenario: 匿名端点
- **WHEN** enabled=true、URL 通过统一目标校验且 api-key 为空
- **THEN** client 连接端点但不发送 Authorization header

#### Scenario: Bearer token
- **WHEN** api-key 非空且目标与用户批准的接收目标一致
- **THEN** MCP 请求使用 Bearer header，日志和错误不输出 key；目标变化后重新确认该目标与凭据绑定

### Requirement: SPEC-WS-UI-001..005 配置入口
用户 SHALL 能通过 config.toml raw 编辑器设置 enabled、mcp-url 和 api-key；raw 文本受桌面认证保护且不脱敏。结构化兼容配置响应 SHALL 保留 websearch 字段元数据和密码型 key 定义。“连接与工具” SHALL 展示兼容搜索的状态、工具批准及高级配置定位，但不把同一连接定义复制为第二份可写配置。保存变更 SHALL 提示重启后端生效，首次使用或定义变化 SHALL 明确提示工具需要批准。

#### Scenario: 修改联网搜索配置
- **WHEN** 用户在 raw TOML 中启用 websearch 并保存
- **THEN** 后端校验并持久化三个点分键，响应包含 restartRequired，界面不将已保存误报为工具已批准可调用

### Requirement: SPEC-WS-AGT-001..003 Agent 工具选择与降级
Agent system prompt SHALL 说明外部实时信息可使用联网搜索，并说明个人活动数据优先本地事件查询与
Wiki 等工具；该说明 MUST NOT 使用 ActivityWatch 品牌名。
搜索未启用或注册失败时，Agent SHALL 继续处理本地数据问题，不声称搜索工具可用。

#### Scenario: 搜索工具缺失
- **WHEN** Agent Toolkit 没有联网搜索工具
- **THEN** 本地事件查询、Wiki、文件、配置和聊天能力不受影响

### Requirement: SPEC-WS-ERR-001..004 初始化错误隔离
MCP client 构建、初始化或注册异常 SHALL 被捕获，失败 client SHALL 关闭或丢弃，Agent 构造 SHALL
继续。诊断 SHALL 明确搜索不可用，但 MUST NOT 包含 API key。连接、初始化和注册 MUST 有有限上限。

#### Scenario: 不可达端点
- **WHEN** 配置端点无法连接或返回非法 MCP 响应
- **THEN** 系统记录受限诊断、跳过搜索 client，并完成 Agent 构造

### Requirement: SPEC-WS-PRV-001..003 搜索隐私
日志和错误 MUST NOT 输出 websearch.api-key。配置 UI 中 key SHALL 使用敏感输入语义；raw 编辑器只在认证本地边界内显示真实值。联网 client SHALL 只发送模型显式提供且经逐次用户批准的工具参数，不自动附加屏幕原文、无障碍树、配置或本地数据库内容。旧配置入口 SHALL 遵循通用 MCP 的目标、工具版本批准及调用确认，不能因名为“搜索”而享有未知工具免确认权限。

#### Scenario: 搜索外部技术问题
- **WHEN** Agent 请求搜索工具且用户确认对应目标和参数
- **THEN** 请求只包含该批准的查询参数，不自动附加本地活动上下文或秘密

### Requirement: SPEC-WS-NON-001..005 功能边界
websearch 兼容能力 MUST NOT 自建爬虫/索引、缓存搜索结果、增加独立结果面板或实现 OAuth。`websearch.*` SHALL 仍只配置一个匿名/Bearer Streamable HTTP 端点；新增多个外部 MCP 连接 SHALL 通过独立连接配置管理。兼容入口 SHALL 遵循统一 HTTPS 公网目标限制，不接受明文、内网或环回目标；既有不合规配置保留原文但显示不可用，不静默改址或发出请求。

#### Scenario: 多服务商切换
- **WHEN** 用户需要另一搜索服务
- **THEN** 可以替换兼容 mcp-url 并重启，或显式新增独立连接；两者均需工具和调用批准，不自动迁移凭据

#### Scenario: 升级后旧地址不符合安全边界
- **WHEN** 旧 websearch 配置为明文或私网地址
- **THEN** 保留配置并显示目标不支持，不将凭据发送到该地址
