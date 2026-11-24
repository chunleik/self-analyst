## Why

beta.19 已支持设置内的连接管理、工具批准、内置只读邮箱和旧联网搜索入口。把这些能力统一到独立 MCP 页面，并根据本次用户确认增加本机外部客户端接入、保存远端草稿及发信，不重建现有连接管理或放宽数据授权。

## What Changes

- **新行为**：顶栏 MCP 页统一呈现连接、邮箱、工具目录、诊断和工具批准，保留设置快捷入口。对外回环 Streamable HTTP 按账户显式启用，使用独立加密令牌，不二次转发第三方工具。
- **新行为**：独立 Maven 邮箱模块、126 和个人 Gmail 应用专用密码预设；固定证书校验的 IMAPS/SMTPS。草稿与发信独立开关，写入逐次确认，SMTP 单独加密保存授权码，未知结果不重试。
- **新行为**：入站第三方连接增加受限旧版 HTTPS SSE 和显式启动的本机 stdio。保持执行工具的定义批准、单次参数确认、撤权和预算边界，不继承进程秘密。
- **行为修改**：停用旧 `websearch.*` 活跃配置；不拉取或迁移旧密钥。可用 MCP 页的默认停用 Parallel Search 预设替代。
- **不在本次支持范围**：要求 OAuth 的 Exchange Online、Gmail Workspace、企业邮箱及自定义 IMAP/SMTP 地址。认证与任意私网目标须另做模型与测试，不借固定预设放宽。stdio 未提供秘密环境变量通道。只支持本机原生客户端，不为云端连接器开通公网访问。

## Capabilities

### New Capabilities

- `mcp-management`：独立 MCP 页面和本机邮箱发布管理。

### Modified Capabilities

- `mcp-integrations`：第三方传输范围、启用和确认。
- `mail-integration`：固定预设、可选外部共享、草稿及发信。
- `web-search`、`user-configuration`、`agent-runtime`：旧配置退役及通用 MCP 接入。

## Impact

模块 `self-analyst-mail` 复用已有 Java 实现及依赖，`self-analyst-app` 负责授权、原子配置及桌面 UI。回环服务仍与现有 DesktopServer 共存。兼容已保存的普通邮箱和通用 HTTP 连接；旧联网搜索活跃键保留可解析但无运行效果。严格校验与 Windows 构建由现有 CI 工作流完成。
