## Context

SelfAnalyst 当前在 `self-analyst-app` 中使用 AgentScope 2.x 驱动 `SelfAnalystAgent`，其串行 `Toolkit` 原生支持 `registerMcpClient` / `removeMcpClient` / `closeMcpClients`，依赖树中已包含 `io.modelcontextprotocol.sdk:mcp-core`。现有联网搜索通过 `SelfAnalystAgent.registerWebSearchMcp` 硬编码接入单一 Streamable HTTP 端点，使用 `config.toml` 的 3 个扁平键且必须重启生效。项目坚持本地优先、显式授权、敏感凭据文件权限隔离（参考 `ChatGptCredentialStore`）以及严格的内容隐私边界。动机与变更范围见 [proposal.md](proposal.md)。

## Goals / Non-Goals

**Goals:**
- 建立统一的 MCP 服务管理层（`McpManager`），同时服务于内部 `SelfAnalystAgent`（MCP Host/Client）与外部 MCP 客户端（Streamable HTTP MCP Server）。
- 实现免重启热生效：增删、修改、启停 MCP 服务或切换单工具禁用状态后，安全更新内部 `Toolkit` 与对外 MCP Server 路由表，不中断正在进行的对话轮次。
- 以 `self-analyst-mail` 纯 Java 模块提供零外部运行时依赖的多账号 IMAP/SMTP 邮箱 MCP 服务，为后续扩展日历、笔记等内置 MCP 树立统一 Provider 范式。
- 在桌面端顶栏提供独立的 `MCP` 一级 Tab 页，统一管理所有内置与外部 MCP 服务（含收编后的联网搜索预设）。
- 将旧 `websearch.*` 配置键干净退役至 `DeprecatedKeys`，避免保留双轨配置复杂度。

**Non-Goals:**
- 不提供向公网或非回环网卡开放 MCP Server 的能力（仅绑定 `127.0.0.1`）。
- 不实现面向 Gmail / Microsoft 365 的 OAuth2 交互登录流（一期采用标准 IMAP/SMTP + 应用专用密码/授权码，后续可平滑扩展认证类型）。
- 不在后台轮询同步邮件，也不将邮件正文持久化到 `events.db`、Wiki 或本地语义索引。
- 不支持将外部接入的第三方 MCP 服务二次转发暴露给外部客户端（一期仅允许内置服务对外暴露，避免凭据二次扩散与循环转发）。
- 不做旧 `config.toml` 中 `websearch.*` 到新 `servers.json` 的自动数据迁移。

## Decisions

### 1. 混合双面架构：协议无关的 `McpProvider` 抽象
- **决策**：在 `com.selfanalyst.mcp` 中定义统一的 `McpProvider` 抽象，屏蔽内置能力与外部 MCP 服务的差异。内置邮箱（`EmailMcpProvider`）直接执行本地 Java 逻辑并返回标准工具定义与调用结果；外部服务（`ExternalMcpProvider`）通过 AgentScope `McpClientBuilder` 连接 `streamable-http` / `sse` / `stdio`。`McpManager` 统一将已启用 Provider 桥接到：
  1. 内部 `SelfAnalystAgent` 的串行 `Toolkit`（使用 `<serverId>__<toolName>` 命名防冲突）；
  2. 对外 `McpHttpServerEndpoint`（使用 `io.modelcontextprotocol.sdk:mcp-core` 的 Streamable HTTP 服务端能力挂载到现有 Javalin 服务器）。
- **备选方案**：让 SelfAnalyst 通过 stdio 启动外部开源邮箱 MCP（如 Node/Python 包）。
- **取舍理由**：Windows 桌面便携版不保证用户本机装有 `npx`/`uvx`，外部进程的环境问题与凭据边界不可控；内置 Java Provider 零外部依赖、打包可控，同时外部通用 MCP 接入仍面向高级用户完整开放。

### 2. 配置与凭据独立持久化，不塞入 `config.toml`
- **决策**：服务定义存于 `{memory.dir}/mcp/servers.json`（原子写入），敏感凭据（邮箱授权码、外部 MCP Bearer Token、stdio 敏感 env、对外 MCP Server Bearer Token）独立存于 `{memory.dir}/mcp/credentials.json`（复用 `ChatGptCredentialStore` 的文件锁、原子替换、POSIX `0700`/`0600` 或 Windows 当前用户 ACL 策略）。
- **备选方案**：在 `config.toml` 中引入数组表 `[[mcp.servers]]`。
- **取舍理由**：现有 `SupportedKeys` 为静态点分键白名单且 raw 编辑器面向原文展示；多服务动态结构与敏感凭据放入 `config.toml` 会破坏现有配置校验模型并扩大凭据暴露面。

### 3. 对外 MCP Server 路由与独立鉴权
- **决策**：复用现有 `DesktopServer`（`127.0.0.1`）挂载：
  - 单服务端点：`/mcp/{serverId}`（工具名保持干净，如 `search_emails`）；
  - 聚合端点：`/mcp/all`（汇总所有已启用对外暴露的内置服务，工具名带 `<serverId>__` 前缀）。
  认证采用独立持久化的 MCP Bearer Token（首次开启对外暴露时自动生成 256-bit 随机令牌并存入 `credentials.json`，支持 UI 显式复制与轮换），不复用桌面端每次启动刷新的临时 session token。
- **备选方案**：新起独立 HTTP 端口或复用桌面临时 token。
- **取舍理由**：复用现有回环服务器可减少端口管理与生命周期复杂度；独立持久化 Token 保证桌面重启后 Claude Desktop / Cursor 无需每次修改配置（若使用动态端口，UI 显式提示用户当前端口及固定端口配置方式）。

### 4. 热生效与并发安全
- **决策**：`McpManager` 维护每个服务的独立状态机（`DISABLED` / `CONNECTING` / `CONNECTED` / `ERROR`）与有限超时（默认初始化 20s、调用 30s）。配置变更时：
  - 先持久化并更新目标 Provider；
  - 对内部 `Toolkit` 的工具增删在串行同步边界内执行，不中断进行中的单次工具调用，下一轮模型推理即看到新工具集，同时动态刷新 Agent System Prompt 中的已启用 MCP 能力摘要；
  - 对外 MCP 端点即时按最新路由表响应 `tools/list` 与 `tools/call`。
- **取舍理由**：避免每次新增邮箱或调整工具开关都要求用户重启整个桌面后端。

### 5. 多邮箱账号与模块拆分
- **决策**：新建轻量 Maven 模块 `self-analyst-mail`，引入 `org.eclipse.angus:angus-mail`（与 `jakarta.mail-api`）并复用现有 `jsoup`。每个邮箱账号对应一条 `BUILTIN_EMAIL` 实例，支持同时配置多个账号（如工作邮箱、个人邮箱）。只读工具（`list_folders`、`search_emails`、`read_email`）与 `create_draft` 默认启用，`send_email` 默认加入 `disabledTools` 禁用。
- **取舍理由**：独立模块与 `self-analyst-file` / `self-analyst-wiki` 边界一致，便于纯单元测试与 mock IMAP/SMTP 验证；默认禁用不可撤回的发信能力符合本地优先与安全可控原则。

### 6. 顶栏独立 `MCP` 一级 Tab 页
- **决策**：在桌面前端顶栏新增 `data-tab="mcp"` 一级 Tab（与看板、知识、会话并列），由独立模块 `mcp.js` / `mcp.css` 承载服务列表、连接状态、对内/对外开关、单工具禁用抽屉/弹窗、添加服务向导（内置邮箱预设 / Parallel Search 联网搜索预设 / 自定义外部 MCP）以及对外 MCP Server 接入配置面板。
- **取舍理由**：MCP 管理属于高频运维与能力编排视图，独立一级页面比嵌套在配置弹窗中更清晰，也便于后续扩展更多内置 MCP 服务。

### 7. 联网搜索无兼容退役
- **决策**：从 `SupportedKeys` 移除 `websearch.enabled`、`websearch.mcp-url`、`websearch.api-key`，将其加入 `DeprecatedKeys`；删除 `SelfAnalystAgent.registerWebSearchMcp` 硬编码逻辑。在 MCP 添加向导中提供 Parallel Search 预设（默认地址 `https://search.parallel.ai/mcp`，类型为 `EXTERNAL_STREAMABLE_HTTP`）。
- **取舍理由**：用户明确要求纳入统一面板且不兼容旧版；放入 `DeprecatedKeys` 遵循项目既有配置退役规范，确保已有 `config.toml` 不会因残留键而启动失败。

## Risks / Trade-offs

- **[桌面随机端口导致外部 MCP 客户端地址变化]** → 在 MCP Tab 的对外接入区清晰展示当前完整 URL，当后端运行在非固定端口时给出提示，引导用户通过既有端口配置固定本地端口。
- **[外部 `stdio` MCP 子进程泄漏或卡死]** → 对每个 `stdio` Provider 施加初始化与调用超时，在服务停用、热更新、连接失败及应用关闭时强制销毁子进程树并记录受限诊断。
- **[多账号或大型邮件正文消耗过多 LLM Token]** → `search_emails` 限制默认与最大返回条数（如默认 20、上限 50），`read_email` 优先提取纯文本或经 `jsoup` 清洗的文本并按字符上限截断，默认不下载附件正文。
- **[IMAP/SMTP 证书或旧服务器兼容性问题]** → 默认强制 TLS/SSL（IMAPS 993 / SMTPS 465 / STARTTLS 587），严格校验证书；清晰返回连接测试诊断信息（如授权码错误、IMAP 未开启、超时），绝不在诊断或日志中回显密码。
- **[Agent 误调用高风险写工具]** → `send_email` 默认禁用；所有服务支持工具级黑名单，被禁用工具既不注册进 Agent `Toolkit` 也不出现在对外 `tools/list` 中，调用被禁用工具直接返回拒绝错误。

## Migration Plan

1. 新增 `self-analyst-mail` 模块并在根 `pom.xml` 与 `self-analyst-app/pom.xml` 注册依赖。
2. 实现 `com.selfanalyst.mcp` 持久化存储、凭据存储、Provider、对外 Streamable HTTP 端点、热生效管理器与 `/desktop/mcp/**` API。
3. 接入 `SelfAnalystAgent` 与 `DesktopServer`，移除旧 `registerWebSearchMcp` 并将 `websearch.*` 移入 `DeprecatedKeys`。
4. 实现桌面端顶栏 `MCP` Tab 页与中英文 i18n 资源，补充 Java 与 Node 回归测试。
5. 同步主规格（`openspec/specs/`）及 `docs/architecture.md`、`README.md`、`README.zh-CN.md`。
