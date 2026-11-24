## 1. 邮箱模块和固定预设

- [x] 1.1 抽出 `self-analyst-mail`，转移原有测试并验证 Maven 反应堆依赖及 JavaMail provider 类路径加载合约。
- [x] 1.2 保留 QQ 和 163，增加 126、个人 Gmail 固定 TLS 预设；验证准确目标绑定、网易 ID、Gmail 授权码语法及不支持的地址。
- [x] 1.3 实现有界草稿 APPEND 与 SMTP、完整逐次批准、撤销/超时/不确定结果；运行 `MailWriteServiceTest`、`MailWriteProtocolTest`。

## 2. 回环发布与管理

- [x] 2.1 实现受认证的账号发布规则、原子非秘密配置、独立 vault 引用、SMTP 凭据、令牌生成和轮换。验证元数据脱敏及不跨目标使用。
- [x] 2.2 实现单服务与聚合 MCP 路由；验证本机 Host/Origin 边界、401/404、目录过滤、代次与一键拒绝；运行 `McpServerEndpointTest`、`McpMailManagementTest`。
- [x] 2.3 在现有 Agent 轮次中接入草稿/发信，保留批准、隔离观测内容和旧轮次撤权，验证不误执行。

## 3. 外部传输、配置和 UI

- [x] 3.1 支持受限 HTTPS SSE 及显式本机 stdio，验证连接、阶段超时、资源关闭、审批租约和不重放；运行 `PinnedSseMcpTransportTest`、`NativeStdioMcpTransportTest`。
- [x] 3.2 退役旧搜索配置，新增默认关闭的 Parallel Search 预设；运行 `ConfigTest`、`ConfigToolsTest`、`DesktopIntegrationControllerTest`。
- [x] 3.3 实现顶栏 MCP 页、令牌和逐次写入审批，扩展中英文与合成 UI 检查；运行 Node 全量测试、提供 CI 合成截图脚本。

## 4. 文档、验证及归档

- [x] 4.1 同步架构、隐私、邮箱/连接指南及两份 README，明确未连接真实服务和未支持的范围。
- [x] 4.2 运行 Maven 全量与 OpenSpec 严格校验，同步主规格并归档。远端必需检查属于后续合并门禁。

## 验证记录

- Java 21 `mvn clean test`：1518 项，1510 通过、8 跳过、0 失败。Node UI：513 项，481 通过、32 跳过、0 失败。
- `openspec validate add-mcp-tool-management --strict` 和 `git diff --check` 通过。使用合成账号、MCP、IMAP/SMTP，未登录真实邮箱或验收具体客户端。
- Windows 必需检查及 CI 合成截图在远端运行；本地 Chromium 受环境限制，未生成截图。
