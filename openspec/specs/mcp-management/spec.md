# mcp-management Specification

## Purpose
定义 SelfAnalyst 的通用 MCP 工具管理平台，涵盖多 MCP 服务（内置 Provider 与外部服务）的注册与凭据隔离、对内 Agent 热挂载与工具级权限控制、对外本地回环 Streamable HTTP MCP Server 暴露与鉴权，以及桌面端顶栏独立 MCP 管理页面。

## Requirements

### Requirement: SPEC-MCP-SRV-001..006 对外本地 MCP Server 暴露与鉴权
系统 SHALL 在现有本地回环服务（仅限 `127.0.0.1`）上提供标准 Streamable HTTP MCP Server 能力，支持按服务隔离的端点 `/mcp/{serverId}` 以及汇总全部已对外暴露服务的聚合端点 `/mcp/all`。服务对外暴露默认关闭；仅显式开启对外暴露的内置服务 SHALL 可经由上述端点被外部 MCP 客户端发现和调用，外部引入的第三方 MCP 服务 MUST NOT 被二次转发暴露。对外 MCP 端点 MUST 校验独立持久化的 Bearer Token，未携带或携带错误令牌的请求 MUST 返回未授权错误；令牌 SHALL 原生系统凭据库加密持久化，系统库不可用时失败，不回退到明文文件。仅在显式生成或轮换时展示新的令牌，旧令牌及待批准请求立即失效。

#### Scenario: 外部客户端携带有效令牌访问单服务端点
- **WHEN** 某内置 MCP 服务已开启对外暴露，外部 MCP 客户端携带有效 Bearer Token 向 `/mcp/{serverId}` 发起初始化、`tools/list` 与 `tools/call`
- **THEN** 服务端按 MCP 协议返回该服务显式允许的工具，写操作只有经过受管桌面逐次审批才执行

#### Scenario: 外部客户端访问未鉴权或未对外暴露的服务
- **WHEN** 外部请求缺少或携带错误 Bearer Token，或目标服务未开启对外暴露
- **THEN** 服务端分别拒绝请求（401 未授权或 404/不可用），不泄露内部凭据或未暴露服务列表

#### Scenario: 轮换对外 MCP Bearer Token
- **WHEN** 用户在桌面端执行对外 MCP 令牌轮换
- **THEN** 系统生成并持久化新令牌，旧令牌对 `/mcp/**` 立即失效，新令牌生效

### Requirement: SPEC-MCP-UI-001..005 桌面端顶栏独立 MCP Tab 页
桌面端主导航顶栏 SHALL 提供独立的一级 `MCP` Tab 页（与看板、知识、会话并列），并完整提供中英文国际化文案。该页面 SHALL 展示：对外 MCP Server 接入信息条（监听端点、令牌配置状态、显式复制与轮换操作及客户端配置 JSON 一键复制）、已添加 MCP 服务列表（含运行状态、对内/对外开关、连接测试、工具权限管理、编辑与删除），以及添加服务向导（内置邮箱预设、联网搜索预设与自定义外部 MCP）。执行连接测试 SHALL 为显式诊断操作，具有有限超时且不自动修改未保存的服务配置。

#### Scenario: 通过顶栏切换进入 MCP 管理页面
- **WHEN** 用户点击主导航顶栏的 `MCP` Tab
- **THEN** 界面切换展示 MCP 服务列表、对外 MCP 接入信息条及添加服务入口

#### Scenario: 测试单个 MCP 服务连接
- **WHEN** 用户在服务卡片或编辑表单中触发连接测试
- **THEN** 后端在有限超时内验证连通性与工具发现并返回状态及工具摘要，不将敏感凭据写入返回诊断或日志
