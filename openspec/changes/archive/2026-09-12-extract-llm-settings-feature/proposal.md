## Why

当前大模型设置需要用户编辑整份 TOML，连接字段、探测和运行状态混在通用配置功能中，难以独立维护。以 menu-harness 的模型设置交互为参考，建立独立的模型连接管理功能，让用户通过表单配置并验证模型，同时复用 SelfAnalyst 已有的配置解析和热更新机制。

## What Changes

- 新增独立模型设置功能：单个 OpenAI-compatible 连接的查询、局部保存、模型发现和最小生成测试，配套独立前端组件与 Java 应用内模块。
- 新增连接预设、Base URL、只写 API Key、可发现或手工填写的模型、温度、单次最大输出设置；预设只辅助填写，不构成多 Provider 持久化目录。
- 新增 `/desktop/llm-settings` 查询与保存、`/presets`、`/test` 和 `/discover-models` 接口，前端不解析或提交整份 TOML。
- 修改配置入口交互：默认展示“模型设置”，以“高级配置”保留原文编辑器。两者切换和关闭均保护未保存草稿。
- 新模型设置保存定向更新既有五个 `llm.*` 键，保留其它配置、注释及排版；沿用统一解析、候选资源准备、提交锁和运行版本发布。
- 凭据查询只返回配置状态和来源；保留、替换、显式清空、恢复环境变量兜底具有明确语义。测试与模型发现使用草稿，不自动保存或切换运行模型。
- 本次属于新增功能和既有交互修改，非历史文档基线迁移。原有 raw、结构化配置及 Agent 工具接口继续兼容。

## Capabilities

### New Capabilities

- `llm-settings`: 独立模型连接设置、预设、凭据操作、模型发现、测试、定向保存与生效状态。

### Modified Capabilities

- `user-configuration`: 允许模型结构化设置与高级原文编辑共存，明确新专用保存入口的注释保真语义以及草稿切换行为。

## Impact

- `self-analyst-app`：新增 `com.selfanalyst.llm.settings` 功能包和桌面控制器；对接 `ConfigApplicationService`、`TomlSupport`、`LlmRuntimeManager`，注册受桌面认证保护的新路由。
- 桌面 UI：新增独立模型设置脚本和样式，调整配置窗口、API 封装、事件及状态管理，补全正式语言资源。
- 测试及文档：覆盖配置保真、凭据、探测、草稿、热更新和兼容接口；更新用户说明及架构文档。
- 不引入新的服务进程、端口、Maven 模块、前端框架或运行时 Node 依赖；不依赖 menu-harness 的运行服务或导入用户密钥。
- 本期维持单一 OpenAI-compatible 运行连接，不包含多 Provider CRUD、Anthropic/Gemini 原生协议、推理强度、图片输入、模型容量目录、路由、负载均衡及计费平台。既有 Agent、摘要和压缩行为继续遵循主规格。
