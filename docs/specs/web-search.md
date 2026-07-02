# SelfAnalyst Agent 联网搜索 SDD 规格说明书

> Specification-Driven Development spec. 本文档定义 Agent 通过 MCP 接入联网搜索的行为契约。实现必须可追溯至本文档中的规格 ID。

---

## 1. 文档元信息

| 属性 | 值 |
|------|-----|
| 功能名称 | Agent 联网搜索（Web Search via MCP） |
| 文档状态 | Implemented |
| 日期 | 2026-06-18 |
| 目标入口 | `SelfAnalystAgent` 构造期注册 MCP 工具 |
| 主要后端逻辑 | `self-analyst-app/src/main/java/com/selfanalyst/agent/SelfAnalystAgent.java` |
| 配置入口 | `self-analyst-app/src/main/java/com/selfanalyst/config/Config.java` |
| 桌面配置 UI | `self-analyst-app/src/main/resources/desktop-ui/config.js`、`DesktopConfigController.java` |
| 默认服务商 | Parallel Search MCP（Streamable HTTP，匿名端点） |
| 规格前缀 | `SPEC-WS-*` |

---

## 2. 背景和当前状态

当前 `SelfAnalystAgent` 基于 agentscope `ReActAgent` + `Toolkit`，已注册 `ActivityWatchTools` 和 `WikiTools`，只能回答本地活动数据相关问题，无法获取实时的外部网络信息（最新资讯、技术文档、外部事实）。

agentscope 1.0.12 已传递引入官方 MCP Java SDK（`io.modelcontextprotocol.sdk:mcp:0.17.0`），`Toolkit` 提供 `registerMcpClient(McpClientWrapper)`，可将远程 MCP server 的工具自动拉取并注册。本功能据此为 Agent 增加联网搜索能力，默认接入 Parallel Search MCP 的匿名端点（免费、无需 API key）。

---

## 3. 目标

- **SPEC-WS-GOAL-001**: Agent 必须具备实时联网搜索能力，用于回答本地数据无法覆盖的外部信息问题。
- **SPEC-WS-GOAL-002**: 默认接入方式必须是无需用户配置 API key 即可使用（匿名端点）。
- **SPEC-WS-GOAL-003**: 联网搜索必须可通过配置开关、可切换端点、可选填 API key。
- **SPEC-WS-GOAL-004**: 搜索后端初始化失败（网络不可达、超时）不得阻断 Agent 启动。
- **SPEC-WS-GOAL-005**: 涉及用户个人活动数据的问题必须仍优先使用本地 ActivityWatch / Wiki 工具。

---

## 4. 非目标

- **SPEC-WS-NON-001**: 首版不自实现搜索爬虫或索引，只消费现成 MCP server。
- **SPEC-WS-NON-002**: 首版不实现多服务商运行时切换 UI，只支持单一端点配置。
- **SPEC-WS-NON-003**: 首版不缓存搜索结果（由 MCP server 侧负责）。
- **SPEC-WS-NON-004**: 首版不在前端单独展示搜索结果面板，结果仅供 Agent 推理。
- **SPEC-WS-NON-005**: 首版不实现 OAuth 登录流程，只支持匿名或 Bearer token。

---

## 5. 架构与集成

- **SPEC-WS-ARCH-001**: 联网搜索必须以 MCP 客户端形式注册进同一个 `Toolkit`，与本地工具共用工具调用链路。
- **SPEC-WS-ARCH-002**: 必须使用 agentscope 自带的 `io.agentscope.core.tool.mcp.McpClientBuilder`，不得手写 HTTP/工具 schema。
- **SPEC-WS-ARCH-003**: 远程端点必须使用 Streamable HTTP 传输（`streamableHttpTransport(url)`）。
- **SPEC-WS-ARCH-004**: 注册通过 `toolkit.registerMcpClient(client)` 完成，由 SDK 负责 `initialize` 与 `listTools`。
- **SPEC-WS-ARCH-005**: 客户端构建与注册必须设置超时（连接/初始化/注册阶段均有上限），避免无限阻塞。

---

## 6. 配置契约

### SPEC-WS-CFG-001: 配置项

| 属性键 | 环境变量 | 默认值 | 含义 |
|--------|----------|--------|------|
| `websearch.enabled` | `WEBSEARCH_ENABLED` | `false` | 是否启用联网搜索（隐私默认关闭，按需开启） |
| `websearch.mcp-url` | `WEBSEARCH_MCP_URL` | `https://search.parallel.ai/mcp` | MCP server 端点 |
| `websearch.api-key` | `WEBSEARCH_API_KEY` | （空） | 可选 Bearer token，留空则匿名 |

- **SPEC-WS-CFG-002**: `Config` record 必须暴露 `webSearchEnabled()`、`webSearchMcpUrl()`、`webSearchApiKey()`。
- **SPEC-WS-CFG-003**: `websearch.enabled = false` 时不得创建任何 MCP 客户端、不得发起网络请求。
- **SPEC-WS-CFG-004**: `websearch.mcp-url` 为空时等同于禁用。
- **SPEC-WS-CFG-005**: `websearch.api-key` 非空时必须以 `Authorization: Bearer <key>` 头发送；为空时不得发送该头。
- **SPEC-WS-CFG-006**: 三个配置项的变更必须标记为 `restartRequired`，因为工具在 Agent 构造期注册。

---

## 7. 桌面配置 UI

- **SPEC-WS-UI-001**: 配置界面必须包含「联网搜索」分组，字段为：启用开关、MCP 端点、API Key（密码框）。
- **SPEC-WS-UI-002**: 前端字段键必须为 `webSearchEnabled` / `webSearchMcpUrl` / `webSearchApiKey`，并由后端映射到对应 `websearch.*` 属性。
- **SPEC-WS-UI-003**: API Key 字段必须为 `password` 类型，提示留空则匿名使用。
- **SPEC-WS-UI-004**: 保存后必须提示需重启后端方可生效。
- **SPEC-WS-UI-005**: `GET /desktop/config` 必须返回 `websearch` 分组及字段元信息。

---

## 8. Agent 行为规格

- **SPEC-WS-AGT-001**: 系统提示必须引导模型：需要实时/外部网络信息时使用联网搜索工具。
- **SPEC-WS-AGT-002**: 系统提示必须声明：涉及用户个人活动数据时优先 ActivityWatch / Wiki 工具。
- **SPEC-WS-AGT-003**: 搜索工具不可用（未启用或注册失败）时，Agent 必须仍能正常处理本地数据问题。

---

## 9. 错误处理和降级

- **SPEC-WS-ERR-001**: MCP 客户端构建、初始化或注册抛出的任何异常必须被捕获，仅记录日志，不向上抛出。
- **SPEC-WS-ERR-002**: 注册失败时 Agent 必须照常完成构造，仅缺少搜索能力。
- **SPEC-WS-ERR-003**: 初始化与注册必须有超时上限，端点不可达时不得长时间阻塞启动。
- **SPEC-WS-ERR-004**: 失败日志必须可读地说明搜索 MCP 不可用，且不得输出 API key。

---

## 10. 隐私和安全

- **SPEC-WS-PRV-001**: 日志与错误信息不得输出 `websearch.api-key`。
- **SPEC-WS-PRV-002**: 桌面配置 UI 不得明文回显已保存的 API key 给非必要场景（密码框）。
- **SPEC-WS-PRV-003**: 发送给搜索 MCP 的查询不得附带用户屏幕原文/OCR/UIA 原始内容，只发送用户问题或必要关键词。

---

## 11. 实现文件和修改范围

### 11.1 修改文件

- `self-analyst-app/src/main/java/com/selfanalyst/agent/SelfAnalystAgent.java`
- `self-analyst-app/src/main/java/com/selfanalyst/config/Config.java`
- `self-analyst-app/src/main/java/com/selfanalyst/desktop/controller/DesktopConfigController.java`
- `self-analyst-app/src/main/resources/desktop-ui/config.js`
- `self-analyst-integration-test/self-analyst-app-test/src/main/java/com/selfanalyst/integration/app/AppVerification.java`

### 11.2 不应修改

- `self-analyst-content`、`self-analyst-audio`、`self-analyst-wiki` 模块逻辑
- `self-analyst-desktop/src-tauri/*`

---

## 12. 测试规格

- **SPEC-WS-TST-001**: `Config.load()` 在默认情况下 `webSearchEnabled` 为 `true`，`webSearchMcpUrl` 为 Parallel 默认端点。
- **SPEC-WS-TST-002**: `websearch.enabled=false` 时构造 Agent 不发起网络请求。
- **SPEC-WS-TST-003**: 提供不可达端点时，Agent 构造不抛异常且可正常处理本地问题。
- **SPEC-WS-TST-004**: `GET /desktop/config` 响应包含 `websearch` 分组及三个字段。
- **SPEC-WS-TST-005**: `PUT /desktop/config` 保存 `websearch.*` 后写入用户配置并返回 `restartRequired`。

### 12.1 前端静态检查

- `config.js` 的 `sections` 包含 `id: "websearch"` 分组及三个字段键。

### 12.2 构建测试

```powershell
mvn -pl self-analyst-app -am compile
```

验收：Maven exit code 为 0。

---

## 13. 验收标准

- **SPEC-WS-ACC-001**: 默认配置下 Agent 启动即具备联网搜索能力，无需用户填写 API key。
- **SPEC-WS-ACC-002**: 搜索端点不可达时 Agent 仍正常启动并回答本地问题。
- **SPEC-WS-ACC-003**: 配置界面可开关联网搜索、修改端点、填写 API key，保存后提示需重启。
- **SPEC-WS-ACC-004**: 涉及个人活动数据的问题仍优先使用本地工具。
- **SPEC-WS-ACC-005**: 日志不泄露 API key。
- **SPEC-WS-ACC-006**: `mvn -pl self-analyst-app -am compile` 通过。

---

## 14. 规格追溯矩阵

| 规格 ID | 目标文件/组件 | 验证方式 |
|---------|---------------|----------|
| SPEC-WS-GOAL-* | 本文档、整体功能 | 规格审查 |
| SPEC-WS-NON-* | 功能范围 | 代码审查 |
| SPEC-WS-ARCH-* | `SelfAnalystAgent.registerWebSearchMcp` | 代码审查 + 单元测试 |
| SPEC-WS-CFG-* | `Config`, `DesktopConfigController` | 单元测试 |
| SPEC-WS-UI-* | `config.js`, `DesktopConfigController` | 静态检查 + Controller 测试 |
| SPEC-WS-AGT-* | `SelfAnalystAgent.buildSystemPrompt` | 代码审查 |
| SPEC-WS-ERR-* | `SelfAnalystAgent.registerWebSearchMcp` | 单元测试 |
| SPEC-WS-PRV-* | 日志与 prompt 构建 | 隐私检查 |
| SPEC-WS-TST-* | 测试文件 | `mvn test` |
| SPEC-WS-ACC-* | 全功能链路 | 验收测试 |
