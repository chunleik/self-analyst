## 1. 内置多账号邮箱模块（`self-analyst-mail`）

- [ ] 1.1 在根 `pom.xml` 注册新子模块 `self-analyst-mail`，引入 `jakarta.mail-api`、`org.eclipse.angus:angus-mail` 及已有 `jsoup` 依赖，并在 `self-analyst-app/pom.xml` 引入该模块；运行 `mvn -pl self-analyst-mail -am test-compile` 验证模块编译通过。
- [ ] 1.2 在 `self-analyst-mail` 中实现邮箱账号配置模型、常见服务商预设目录（QQ、163/126、Gmail、Outlook/Exchange、企业微信邮、阿里企业邮、自定义 IMAP/SMTP）及默认 TLS/SSL 校验；新增 `MailPresetCatalogTest.java` 并运行 `mvn -pl self-analyst-mail test` 验证预设与安全校验通过。
- [ ] 1.3 实现 IMAP/SMTP 客户端与 5 个标准邮箱工具（`list_folders`、`search_emails`、`read_email`、`create_draft`、`send_email`），覆盖有限连接/读取超时、HTML 文本清洗与正文截断、不自动下载附件正文以及凭据脱敏诊断；新增 `MailClientTest.java` 与 `MailToolsTest.java` 并运行 `mvn -pl self-analyst-mail test` 验证全部用例通过。

## 2. MCP 注册表、凭据隔离与 Provider 抽象

- [ ] 2.1 在 `self-analyst-app/src/main/java/com/selfanalyst/mcp/` 实现 `McpRegistryStore`（原子读写 `{memory.dir}/mcp/servers.json`）与 `McpCredentialStore`（文件锁 + 原子替换 + 当前用户 ACL / `0600` 权限管理 `{memory.dir}/mcp/credentials.json`，涵盖服务凭据与对外 MCP Server Bearer Token）；新增 `McpStoresTest.java` 并运行 `mvn -pl self-analyst-app -am '-Dtest=McpStoresTest' '-Dsurefire.failIfNoSpecifiedTests=false' test` 验证原子写入、权限校验与脱敏。
- [ ] 2.2 实现 `McpProvider` 接口及其两种实现：`EmailMcpProvider`（支持多账号实例、默认禁用 `send_email`）与 `ExternalMcpProvider`（封装 AgentScope `McpClientBuilder`，支持 `streamable-http`、`sse`、`stdio`、有限初始化/调用超时与子进程/连接回收）；新增 `McpProviderTest.java` 并运行针对性测试验证工具发现、禁用过滤与超时/异常资源释放。

## 3. `McpManager` 热生效、Agent 接入与旧 `websearch` 退役

- [ ] 3.1 实现 `McpManager`，统一管理服务状态机（`DISABLED` / `CONNECTING` / `CONNECTED` / `ERROR`）、单服务故障隔离、连接测试、热更新 `SelfAnalystAgent` 串行 `Toolkit`（工具名按 `<serverId>__<toolName>` 隔离）以及动态刷新 Agent System Prompt 中的已启用 MCP 能力摘要；新增 `McpManagerTest.java` 验证热启停、工具级禁用与故障隔离。
- [ ] 3.2 修改 `SelfAnalystAgent`、`SupportedKeys`、`DeprecatedKeys`、`Config` 与 `ConfigTools`：接入 `McpManager` 并在关闭时优雅释放所有 MCP 资源，移除 `registerWebSearchMcp` 硬编码，将 `websearch.enabled`、`websearch.mcp-url`、`websearch.api-key` 从 `SupportedKeys` 移入 `DeprecatedKeys`；更新并运行 `ConfigTest`、`ConfigToolsTest` 与 `DesktopConfigControllerTest` 验证旧配置静默退役且不阻断启动。

## 4. 对外本地回环 MCP Server 端点

- [ ] 4.1 基于 `io.modelcontextprotocol.sdk:mcp-core` 在 `com.selfanalyst.mcp` 实现 Streamable HTTP 服务端适配器，在 `DesktopServer`（仅 `127.0.0.1` 回环）注册 `/mcp/{serverId}` 与 `/mcp/all` 端点，校验独立持久化 Bearer Token，仅暴露显式开启对外暴露的内置服务并过滤禁用工具；新增 `McpServerEndpointTest.java` 并运行针对性测试验证鉴权（401）、未暴露拒绝（404）、令牌轮换、单服务与聚合端点的 `tools/list` 和 `tools/call`。

## 5. 桌面端管理 API 与顶栏独立 `MCP` 一级 Tab 页

- [ ] 5.1 实现 `DesktopMcpController` 并在 `DesktopServer` 注册 `/desktop/mcp/**` 管理路由（服务列表、预设目录、增删改、对内/对外开关、单工具禁用更新、连接测试、对外 MCP 接入信息与令牌轮换）；新增 `DesktopMcpControllerTest.java` 并运行针对性测试验证所有端点的正常路径、参数校验与凭据脱敏。
- [ ] 5.2 在 `self-analyst-app/src/main/resources/desktop-ui/` 修改 `index.html`、`ui.js`、`init.js`、`api.js` 及 `locales/zh-CN.json`、`locales/en-US.json`，新增 `mcp.js` 与 `mcp.css`，在顶栏添加独立 `MCP` 一级 Tab 页（对外接入信息条、服务卡片列表、连接测试、工具权限管理、添加内置邮箱/联网搜索预设/自定义外部 MCP 向导）；新增 `self-analyst-app/src/test/js/mcp.test.mjs` 并运行 `node --test` 验证所有前端交互与 i18n 完整性。

## 6. 全量验证与文档同步

- [ ] 6.1 运行 `mvn test` 验证全量 Java JUnit 5 测试与桌面端 UI Node 测试全部通过，并运行 `openspec validate add-mcp-tool-management --strict` 确认 OpenSpec 校验通过。
- [ ] 6.2 同步更新 `docs/architecture.md`、`README.md`（英文）与 `README.zh-CN.md`（中文），在确认功能边界与文档语言一致后准备规格同步与归档。
