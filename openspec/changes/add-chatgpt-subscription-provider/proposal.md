## Why

当前 SelfAnalyst 仅支持通过 API Key 调用 OpenAI-compatible 模型，不能让用户在受支持的服务端授权协议下选择自己的 ChatGPT 账户连接。新增明确区分 API Key 与 ChatGPT 登录的连接方式，在保留现有配置、数据隐私和运行时热更新约束的同时，为 OAuth 授权、Responses 调用及设置界面建立可验证的行为边界。

## What Changes

- 新增独立的 ChatGPT 登录连接：通过官方动态客户端注册协议、系统浏览器、OAuth authorization code + PKCE 和本机回调完成用户授权。
- 新增多账户选择、受保护的本地 OAuth 凭据存储、过期刷新、状态查询、取消及断开连接。令牌不写入普通 TOML、不返回桌面 UI、不进入日志或错误。
- 新增 ChatGPT Responses 适配、受限模型发现和最小生成测试，接入既有 Agent、摘要、压缩、工具调用与用量计量。
- Responses 工具续轮保留并回传服务端不透明加密推理项，不向用户展示其内容；对确认的订阅限额或限流按账户暂停本进程后续模型工作，显式测试成功或重启后再恢复。
- 修改单连接设置，支持选择既有 API Key 或 ChatGPT 登录方式，保留 API Key 配置和凭据操作；成功登录不等同远端模型可调用。
- 明确支持边界：ChatGPT 订阅不自动等价于 OpenAI API 额度；只使用服务端实际提供并允许的官方授权及模型接口，仅使用官方文档规定的初始动态注册标识及本应用获发身份，不复用其它应用身份，不读取浏览器 Cookie 或 Codex 凭据。服务端未提供所需协议时明确报告不支持，禁止伪报登录或生成成功。
- Embedding 继续使用其独立 API Key 连接，不接收 ChatGPT OAuth 令牌，也不因切换对话连接被自动启用。
- 本次为新增功能和既有连接行为修改，不是历史文档基线迁移。

## Capabilities

### New Capabilities

- `chatgpt-auth`: ChatGPT OAuth 元数据与动态注册、PKCE 本机授权、受保护凭据、刷新及断开连接。
- `chatgpt-responses`: Responses 请求、流式结果、工具调用、模型发现、用量与安全失败行为。

### Modified Capabilities

- `llm-settings`: 单一活动连接的认证方式选择、登录状态、连接草稿、模型发现与测试。
- `user-configuration`: 认证方式配置、OAuth 凭据隔离、模型版本切换及 Embedding 独立边界。
- `agent-runtime`: 根据活动认证方式选择模型适配，保持运行租约、工具及用量语义。

## Impact

- `self-analyst-app` 的配置解析、模型运行管理、桌面认证路由、独立模型设置、LLM 适配和生命周期管理。
- 桌面模型设置 UI、API 封装和中英文资源；复用既有系统浏览器打开机制。
- Windows 便携包的 Java runtime 模块集合加入 OAuth 回环监听所需的 `jdk.httpserver`，并用 Node 源码回归防止漏包；真实 Windows 打包仍需独立验证。
- 针对性 Java 与 Node 回归测试，以及模型设置指南、架构、隐私说明及双语 README。
- 服务端是否为该用户账户开放官方预览动态注册和 Responses 访问是外部前提；实现与测试必须把本地协议验证和真实账户端到端验证区分开。
- 不新增通用代理、不传输采集正文、不改变标题与文件元数据边界，不移除或迁移用户现有 API Key。
