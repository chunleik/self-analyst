## Why

当前 SelfAnalyst 仅在 Agent 构造期硬编码接入了单一的联网搜索 MCP 端点，既无法统一管理多个外部或内置 MCP 服务，也无法把 SelfAnalyst 托管的工具能力通过标准 MCP 协议对外提供给其他客户端（如 Claude Desktop、Cursor 等）。随着个人生产力场景扩展，需要一套既对内赋能 Agent、又可按需对外暴露的通用 MCP 管理平台，并优先内置零外部依赖的多账号邮箱 MCP 服务作为首个落地能力。

## What Changes

- **新行为 — 通用 MCP 管理平台（对内 + 对外）**：
  - 引入统一的 MCP 服务注册、生命周期管理、热生效与错误隔离机制，支持内置 Provider 与外部 MCP 服务（`streamable-http`、`sse`、`stdio`）。
  - 对内：动态将已启用的 MCP 服务工具注册/卸载到 `SelfAnalystAgent` 的串行 `Toolkit`，免重启生效，并按服务 ID 隔离工具名防止多服务冲突。
  - 对外：在现有 `127.0.0.1` 回环 HTTP 服务上提供标准 Streamable HTTP MCP Server 端点（单服务端点 `/mcp/{serverId}` 与聚合端点 `/mcp/all`），采用独立持久化的 Bearer Token 鉴权并默认关闭对外暴露。
  - 凭据隔离：将 MCP 服务定义与敏感凭据分别持久化至 `{memory.dir}/mcp/servers.json` 与受限权限的 `{memory.dir}/mcp/credentials.json`（原子写入、当前用户 ACL / `0600`、日志与 API 脱敏）。
  - 桌面端 UI：在顶部主导航新增独立的 `MCP` 一级 Tab 页，提供服务列表、启停与对外暴露开关、连接测试、工具级禁用控制、添加服务向导及对外 MCP 客户端配置一键复制/Token 轮换。
- **新行为 — 内置多账号邮箱 MCP 服务**：
  - 新增轻量模块 `self-analyst-mail`，基于 Java `jakarta.mail` / `angus-mail` 与现有 `jsoup` 实现本地直连 IMAP/SMTP，零 Node/Python 外部运行时依赖。
  - 支持添加多个独立邮箱账号（各自作为独立 MCP 实例），内置常见邮箱服务商预设（QQ、163/126、Gmail、Outlook/Exchange、企业微信邮、阿里企业邮及自定义 IMAP/SMTP）。
  - 提供 `list_folders`、`search_emails`、`read_email`、`create_draft`、`send_email` 五个工具；只读与草稿默认启用，`send_email` 默认禁用且需用户显式开启；邮件内容仅在调用时按需读取，不自动落盘至 `events.db` 或 Wiki。
- **行为修改 — 联网搜索纳入统一 MCP 管理（**BREAKING**）**：
  - **BREAKING**：移除 `SelfAnalystAgent` 中硬编码的 `websearch` 单点 MCP 注册，以及 `config.toml` 中 `websearch.enabled`、`websearch.mcp-url`、`websearch.api-key` 的活跃配置支持；这三个旧键移入 `DeprecatedKeys` 以静默忽略旧配置文件，不再兼容旧版配置迁移。
  - 联网搜索改为 MCP 管理面板中的内置预设模板（Parallel Search Streamable HTTP），通过统一 MCP 机制启用、测试与热生效。

## Capabilities

### New Capabilities

- `mcp-management`: 通用 MCP 工具管理平台，涵盖多服务注册与凭据隔离、对内 Agent 热挂载与工具级禁用、对外本地回环 Streamable HTTP MCP Server 暴露与 Bearer Token 鉴权，以及桌面端顶栏独立 MCP Tab 页。
- `email-mcp`: 基于纯 Java IMAP/SMTP 的内置多账号邮箱 MCP 服务，涵盖多账号实例、常见服务商预设、工具契约、默认写操作安全策略与隐私边界。

### Modified Capabilities

- `web-search`: 变更联网搜索的配置与生命周期契约——移除 `config.toml` 的 `websearch.*` 活跃键与重启生效机制，统一由 `mcp-management` 的外部 HTTP 预设管理。

## Impact

- **新增模块**：`self-analyst-mail`（Maven 子模块，引入 `org.eclipse.angus:angus-mail` / `jakarta.mail-api` 及现有 `jsoup`）。
- **核心后端（`self-analyst-app`）**：
  - 新增 `com.selfanalyst.mcp` 包（注册表存储、凭据存储、Provider 抽象、外部 MCP 客户端包装、对外 Streamable HTTP MCP Server 端点、`McpManager` 与 `DesktopMcpController`）。
  - 修改 `SelfAnalystAgent`（接入 `McpManager` 动态管理 `Toolkit` 并移除 `registerWebSearchMcp`）、`DesktopServer`（注册 `/desktop/mcp/**` 与 `/mcp/**` 路由）、`SupportedKeys` / `DeprecatedKeys` / `Config` / `ConfigTools`（退役 `websearch.*` 活跃键）。
- **桌面前端（`self-analyst-app/src/main/resources/desktop-ui/`）**：
  - 修改 `index.html`（顶栏新增 `data-tab="mcp"` 一级 Tab）、`ui.js` / `init.js` / `api.js` 及中英文 `locales/*.json`。
  - 新增 `mcp.js` 与 `mcp.css`，并补充 Node UI 测试。
- **文档与规格**：同步更新 `openspec/specs/`、`docs/architecture.md`、`README.md` 与 `README.zh-CN.md`。
