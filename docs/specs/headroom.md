# SelfAnalyst Headroom 集成 SDD 规格说明书

> Specification-Driven Development spec. 本文档定义 SelfAnalyst 与 Headroom 的集成契约：运行时通过本地 Headroom proxy 降低 LLM token 成本，开发期通过 Headroom memory / failure learning 共享 agent 项目知识。

---

## 1. 文档元信息

| 属性 | 值 |
|------|-----|
| 功能名称 | Headroom 集成 |
| 文档状态 | Ready for implementation |
| 日期 | 2026-07-06 |
| 规格前缀 | `SPEC-HR-*` |
| 类型 | 跨模块 Feature Spec |
| 运行时入口 | `SelfAnalystAgent` / LLM client base URL 解析 |
| 配置入口 | `Config`、`application.properties`、`ConfigTools`、桌面配置 UI |
| 开发期入口 | `headroom wrap`、`headroom learn`、仓库 agent 指令文件 |
| 默认状态 | 关闭；不影响现有 LLM 路径 |

---

## 2. 背景和当前状态

SelfAnalyst 的 LLM 流量集中在 OpenAI-compatible 配置上：`llm.base-url`、`llm.model`、`llm.api-key`。聊天、桌面摘要增强、wiki 摘要、文件摘要、OCR/UIA 文本和语音转写文本都会进入 LLM 上下文，token 成本会随采集内容增长。

Headroom 是本地优先的上下文优化层，支持 OpenAI-compatible proxy、SDK、MCP server、跨 agent memory 和 failure learning。对 SelfAnalyst 而言，首选集成方式是本地 proxy：不侵入 Java prompt builder，不把 Headroom 变成必需依赖，并能在不可用时回退原 LLM provider。

---

## 3. 目标

- **SPEC-HR-GOAL-001**: SelfAnalyst 必须能在用户启用后把符合条件的 LLM chat/completion 请求路由到本地 Headroom proxy。
- **SPEC-HR-GOAL-002**: Headroom 未安装、未运行或健康检查失败时，SelfAnalyst 必须回退到原始 `llm.base-url`。
- **SPEC-HR-GOAL-003**: 用户必须能通过配置读取和修改 Headroom 开关、proxy URL 和状态相关设置。
- **SPEC-HR-GOAL-004**: SelfAnalyst 必须保留现有 `UsageMeter` 作为预算拦截和用量展示的权威来源。
- **SPEC-HR-GOAL-005**: Headroom savings/stats 只能作为附加信息展示，不得替代 `UsageMeter`。
- **SPEC-HR-GOAL-006**: 仓库开发流程必须文档化 Headroom memory / failure learning 的使用边界，让 Codex、Claude Code、Cursor 等 agent 能共享项目知识和失败修正经验。

## 4. 非目标

- **SPEC-HR-NON-001**: 首版不 vendor、不复制、不重写 Headroom 源码。
- **SPEC-HR-NON-002**: 首版不在 Java 内部实现内容压缩算法。
- **SPEC-HR-NON-003**: 首版不自动安装 Headroom，不自动启动外部 Python proxy 进程。
- **SPEC-HR-NON-004**: 首版不代理 speech-to-text 原始音频上传请求。
- **SPEC-HR-NON-005**: 首版不向 Headroom 发送截图、图片或任意二进制文件。
- **SPEC-HR-NON-006**: Headroom development memory 不用于存储 SelfAnalyst 用户行为数据、OCR 原文、音频转写原文或 API key。

---

## 5. 架构与集成

### SPEC-HR-ARCH-001: 双轨集成

Headroom 集成分为两条边界清晰的轨道：

1. **运行时 token reduction**：SelfAnalyst 应用进程检测本地 Headroom proxy，并把 OpenAI-compatible LLM 请求路由到 proxy。
2. **开发期 agent memory / failure learning**：仓库维护者在 SelfAnalyst 进程外使用 Headroom CLI / agent wrapper / `headroom learn`。

两条轨道不得共享 SelfAnalyst 用户行为数据。

### SPEC-HR-ARCH-002: LLM 路由边界

LLM 路由只改变模型客户端使用的 base URL，不改变：

- prompt 内容构造规则
- model name
- API key 来源
- `GenerateOptions`
- `UsageMeter` 计量入口
- Agentscope 工具注册链路

### SPEC-HR-ARCH-003: 适用流量

以下文本类流量可通过 Headroom proxy：

- Agent chat
- plain completion summary
- wiki summary
- file summary
- OCR/UIA 提取后的文本
- audio transcription 之后的文本
- tool output / RAG context / file excerpt

以下流量首版不得通过 Headroom：

- 原始麦克风 / loopback 音频
- 截图和图片
- embedding 请求
- Headroom CLI 自身的 memory / learn 数据

### SPEC-HR-ARCH-004: 新增 HeadroomService

App 层应引入一个小型 `HeadroomService` 或等价组件，职责仅限：

- 读取 Headroom 配置
- 执行本地 proxy 健康检查
- 计算 effective LLM base URL
- 暴露状态快照
- 读取可选 savings/stats

该组件不得持有 prompt 原文，不得记录用户活动内容。

---

## 6. 配置契约

### SPEC-HR-CFG-001: 配置项

| 属性键 | 环境变量 | 默认值 | 含义 |
|--------|----------|--------|------|
| `headroom.enabled` | `HEADROOM_ENABLED` | `false` | 是否启用 Headroom proxy 路由 |
| `headroom.proxy-url` | `HEADROOM_PROXY_URL` | `http://127.0.0.1:8787/v1` | OpenAI-compatible proxy base URL |
| `headroom.stats.enabled` | `HEADROOM_STATS_ENABLED` | `true` | 是否读取 Headroom stats/savings |
| `headroom.output-shaper` | `HEADROOM_OUTPUT_SHAPER` | `false` | 是否提示用户在外部 proxy 开启输出 shaping |

- **SPEC-HR-CFG-001a**: `headroom.enabled=false` 时不得访问 Headroom proxy。
- **SPEC-HR-CFG-001b**: `headroom.proxy-url` 为空或非法 URL 时必须视为 Headroom 不可用，并回退 `llm.base-url`。
- **SPEC-HR-CFG-001c**: 以上配置变更首版均需重启后端生效，因为 LLM client 在启动期构造。

### SPEC-HR-CFG-002: 原始 provider 配置保留

Headroom 集成不得删除或覆盖以下原始配置：

- `llm.base-url`
- `llm.model`
- `llm.api-key`
- `embedding.base-url`
- `embedding.model`
- `embedding.api-key`

### SPEC-HR-CFG-003: ConfigTools 支持

`ConfigTools` 必须能展示和修改 Headroom 配置项。输出中 API key 继续按既有规则 masking；Headroom URL 不需要 masking。

---

## 7. 路由和健康状态

### SPEC-HR-ROUTE-001: Effective LLM Base URL

启动时 effective LLM base URL 按以下规则确定：

1. `headroom.enabled=false` → 使用 `llm.base-url`。
2. `headroom.enabled=true` 且 proxy 健康检查成功 → 使用 `headroom.proxy-url`。
3. `headroom.enabled=true` 但 proxy 健康检查失败 → 使用 `llm.base-url`，状态为 `fallback`。

### SPEC-HR-ROUTE-002: 不代理 embedding

首版 embedding client 必须继续使用 `embedding.base-url`。Headroom savings 不得把 embedding 纳入压缩收益统计。

### SPEC-HR-ROUTE-003: 状态枚举

Headroom 状态必须至少包含：

| 状态 | 含义 |
|------|------|
| `disabled` | 用户未启用 Headroom |
| `available` | 启用且 proxy 健康检查成功 |
| `fallback` | 启用但当前使用 `llm.base-url` |
| `unavailable` | 启用但无法连接或 URL 非法 |
| `unknown` | 未完成检查或 stats 不可读 |

### SPEC-HR-ROUTE-004: 健康检查约束

- 健康检查必须只访问 loopback / 用户配置的 proxy URL。
- 健康检查必须设置短超时，避免阻塞启动。
- 健康检查失败不得抛出到 `AppSession` 顶层导致应用启动失败。
- 日志不得包含 prompt、OCR 文本、音频转写文本或 API key。

---

## 8. Savings 和用量展示

### SPEC-HR-STATS-001: UsageMeter 权威性

`UsageMeter` 仍是预算 enforcement 的唯一权威来源。Headroom stats 不得影响 `warn` / `block` 判断。

### SPEC-HR-STATS-002: Stats 快照

如果 `headroom.stats.enabled=true` 且 proxy 提供可读 stats，桌面端可展示：

- Headroom 状态
- proxy URL
- savings 百分比
- original input token estimate
- compressed input token estimate
- output shaping 是否开启
- stats 最近更新时间

### SPEC-HR-STATS-003: Stats 降级

stats 不可读时，LLM 调用不得失败。桌面端必须显示 stats unavailable，而不是隐藏 Headroom 整体状态。

---

## 9. 开发期 memory / failure learning

### SPEC-HR-DEV-001: 作用域

Headroom development memory 只用于 repository development context，可保存：

- 模块入口和真实路径
- 常用构建 / 测试命令
- CodeGraph 使用规则
- 本机环境事实
- 失败后修正出的稳定项目规则

### SPEC-HR-DEV-002: 禁止内容

development memory 不得保存：

- SelfAnalyst 用户个人活动数据
- OCR/UIA 屏幕原文
- 音频转写原文
- API key / token / 密码
- 公司或个人敏感文件内容

### SPEC-HR-DEV-003: Failure learning 工作流

仓库文档必须说明：

- `headroom learn` 默认用于预览建议，不直接写入共享规则。
- `headroom learn --apply` 只能在人工 review 后使用。
- durable team-wide 规则才进入 `AGENTS.md` / `CLAUDE.md`。
- 本机偏好和临时环境修正必须保留在 local ignored 文件中。

### SPEC-HR-DEV-004: 规则质量

通过 failure learning 产生的规则必须满足：

- 不含敏感数据。
- 不与现有 SDD / AGENTS.md 规则冲突。
- 描述稳定行为，而不是一次性错误。
- 能被后续 agent 理解并执行。

---

## 10. 桌面 UI 和 API 可见性

### SPEC-HR-UI-001: 配置 UI

桌面配置 UI 必须包含 Headroom 分组，至少展示：

- 启用开关
- proxy URL
- stats 开关
- output shaping 提示开关

### SPEC-HR-UI-002: 状态展示

桌面端 LLM / 用量区域必须展示 Headroom 当前状态。状态为 `fallback` / `unavailable` 时必须有可读原因。

### SPEC-HR-UI-003: 配置 API

`GET /desktop/config` 和 `PUT /desktop/config` 必须支持 Headroom 配置项，并沿用现有 raw config / version history 行为。

### SPEC-HR-UI-004: Agent 配置工具

Agent 通过 `ConfigTools.getConfig()` 查询配置时，必须看到 Headroom 配置和运行时状态。通过 `setConfigValue` 修改 Headroom 配置时，必须提示重启后生效。

---

## 11. 错误处理和隐私

- **SPEC-HR-ERR-001**: Headroom disabled 时行为必须与当前版本一致。
- **SPEC-HR-ERR-002**: Headroom enabled 但 proxy 不可用时，应用必须启动成功并回退 `llm.base-url`。
- **SPEC-HR-ERR-003**: proxy 连接错误可触发 fallback；provider 认证错误、模型不存在等上游错误不得伪装成 Headroom 不可用。
- **SPEC-HR-ERR-004**: Headroom diagnostics 日志不得输出 API key、prompt 原文、OCR 原文或音频转写原文。
- **SPEC-HR-ERR-005**: stats 读取失败不得影响聊天、摘要、wiki、file worker 的 LLM 调用。

---

## 12. 测试规格

- **SPEC-HR-TST-001**: 默认配置下 `headroom.enabled=false`，effective base URL 等于 `llm.base-url`。
- **SPEC-HR-TST-002**: `headroom.enabled=true` 且 proxy 健康检查成功时，effective base URL 等于 `headroom.proxy-url`。
- **SPEC-HR-TST-003**: `headroom.enabled=true` 且 proxy 健康检查失败时，effective base URL 回退 `llm.base-url`，状态为 `fallback` 或 `unavailable`。
- **SPEC-HR-TST-004**: Headroom 配置项出现在 `ConfigTools.getConfig()`，且 `setConfigValue` 可保存并提示重启。
- **SPEC-HR-TST-005**: `GET /desktop/config` 返回 Headroom 分组；`PUT /desktop/config` 可写入 `headroom.*`。
- **SPEC-HR-TST-006**: stats 读取失败不影响 LLM 调用路径。
- **SPEC-HR-TST-007**: `UsageMeter` 的 block 模式在 Headroom enabled 时仍能阻断调用。
- **SPEC-HR-TST-008**: failure learning 文档检查确认 `headroom learn --apply` 需要人工 review，且禁止保存用户行为数据。

---

## 13. 验收标准

- **SPEC-HR-ACC-001**: 用户未启用 Headroom 时，应用行为和现有 LLM 配置完全一致。
- **SPEC-HR-ACC-002**: 用户手动启动 Headroom proxy 并启用 `headroom.enabled=true` 后，Agent chat / summary 路径使用 proxy base URL。
- **SPEC-HR-ACC-003**: 用户停止 Headroom proxy 后，SelfAnalyst 下次启动能回退到 `llm.base-url` 并显示 fallback 原因。
- **SPEC-HR-ACC-004**: 桌面配置 UI 和 Agent 配置工具均能查看 Headroom 配置与状态。
- **SPEC-HR-ACC-005**: 文档说明 development memory / failure learning 的使用边界和隐私禁区。
- **SPEC-HR-ACC-006**: Maven app 模块测试通过。

---

## 14. 规格追溯矩阵

| 规格 ID | 目标文件/组件 | 验证方式 |
|---------|---------------|----------|
| SPEC-HR-GOAL-* | 本文档、整体功能 | 规格审查 |
| SPEC-HR-NON-* | 功能范围 | 代码审查 |
| SPEC-HR-ARCH-* | `HeadroomService`, `SelfAnalystAgent`, LLM client construction | 单元测试 + 代码审查 |
| SPEC-HR-CFG-* | `Config`, `application.properties`, `ConfigTools` | 单元测试 |
| SPEC-HR-ROUTE-* | `HeadroomService`, `SelfAnalystAgent` | 单元测试 |
| SPEC-HR-STATS-* | `HeadroomService`, `/desktop/usage` 或配置/状态 API | 单元测试 + Controller 测试 |
| SPEC-HR-DEV-* | `docs/headroom.md`, `AGENTS.md`, `CLAUDE.md`, docs | 文档审查 |
| SPEC-HR-UI-* | `DesktopConfigController`, `desktop-ui/config.js`, `ConfigTools` | Controller 测试 + 静态检查 |
| SPEC-HR-ERR-* | `HeadroomService`, app startup path, logs | 单元测试 + 隐私检查 |
| SPEC-HR-TST-* | 测试文件 | `mvn test` |
| SPEC-HR-ACC-* | 全功能链路 | 验收测试 |
