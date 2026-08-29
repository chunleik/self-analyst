# CLAUDE.md — SelfAnalyst 项目约定

## 通用仓库规范

`AGENTS.md` 是本项目通用仓库规范的权威来源。开始工作前必须阅读并遵循其中的项目结构、构建与测试、编码风格、提交、文档语言、智能体及 CodeGraph 规则；修改通用规范时，必须同步检查并更新本文件，避免规则冲突。

当前项目的所有文档统一使用简体中文，包括 README、`docs/` 下的文档及仓库中的其他说明性 Markdown 文件。命令、代码标识符、文件路径、协议名称、产品名称及其他不宜翻译的专有名词可以保留英文。

## SDD 文档索引

SelfAnalyst 遵循规格驱动开发（Specification-Driven Development，SDD）。每个模块和主要功能都有正式的规格文档。

### 架构

| 文档 | 用途 |
|------|------|
| [docs/architecture.md](docs/architecture.md) | 系统架构、模块边界与数据流 |

### 模块规格

| 文档 | 模块 | 关键字前缀 |
|------|------|------------|
| [docs/specs/core.md](docs/specs/core.md) | 应用模块（CLI、配置、记忆、智能体） | `SPEC-CFG-*`、`SPEC-MEM-*`、`SPEC-AGT-*`、`SPEC-CLI-*` |
| [docs/specs/content.md](docs/specs/content.md) | 内容/OCR/UIA 模块 | `SPEC-CTX-*`、`SPEC-OCR-*` |
| [docs/specs/audio.md](docs/specs/audio.md) | 音频采集与转写模块 | `SPEC-AU-*` |
| [docs/specs/file.md](docs/specs/file.md) | 文件监控、摘要与语义索引模块 | `SPEC-FILE-*` |
| [docs/specs/integration-test.md](docs/specs/integration-test.md) | 集成测试模块组 | `SPEC-ITEST-*` |

### 功能规格

| 文档 | 功能 | 关键字前缀 |
|------|------|------------|
| [docs/specs/desktop.md](docs/specs/desktop.md) | Tauri 桌面端外壳 | `SPEC-DSK-*` |
| [docs/specs/desktop-chat-tab.md](docs/specs/desktop-chat-tab.md) | 桌面端 UI 的聊天标签页 | `SPEC-CHAT-TAB-*` |
| [docs/specs/llm-wiki.md](docs/specs/llm-wiki.md) | LLM Wiki 多层级摘要与语义索引 | `SPEC-WIKI-*`、`SPEC-WIKI-SEM-*`、`SPEC-WIKI-EMB-*` |
| [docs/specs/behavior-advice.md](docs/specs/behavior-advice.md) | 基于行为的建议与鼓励展示卡片 | `SPEC-ADV-*` |
| [docs/specs/web-search.md](docs/specs/web-search.md) | 智能体通过 MCP 使用 Parallel Search 进行网络搜索 | `SPEC-WS-*` |
| [docs/specs/accessibility-sidecar.md](docs/specs/accessibility-sidecar.md) | UIA → 常驻 Rust 边车程序及操作系统中立的无障碍树协议（取代 PowerShell 单次运行方式，并为 macOS 预留扩展能力） | `SPEC-AXS-*` |
| [docs/specs/llm-budget.md](docs/specs/llm-budget.md) | LLM 令牌用量限制与每日预算（`max_tokens`、可配置的 `maxIters`、计量及 `off`/`warn`/`block` 预算模式） | `SPEC-BUDGET-*` |
| [docs/specs/desktop-config-editor.md](docs/specs/desktop-config-editor.md) | 桌面端“配置”纯文本编辑器的原始设计；当前文件格式与接口修订见 `config-toml.md`，配置历史已移除 | `SPEC-CFGUI-*` |
| [docs/specs/config-toml.md](docs/specs/config-toml.md) | 用户级配置从 `.properties` 迁移到 TOML（`config.toml`）：格式、键拍平、类型校验、启动时自动迁移存量配置、原始文本编辑器与完整双语注释模板 | `SPEC-TOML-*` |
| [docs/specs/chat-session-store.md](docs/specs/chat-session-store.md) | 桌面端会话改为由后端持久化：在 `{memoryDir}/chat-sessions/` 中按会话分片，提供索引及 REST CRUD（取代 `localStorage`，不迁移旧数据，会话数不设上限，并按会话摘要搜索） | `SPEC-CSP-*` |
| [docs/specs/chat-session-sqlite-store.md](docs/specs/chat-session-sqlite-store.md) | 会话存储收敛为以 SQLite 单库（WAL）作为唯一权威来源，并使用 FTS5 trigram 搜索；取代分片、投影、`index.state` 及 tombstone 恢复机制（REST/前端契约不变，包含存量迁移） | `SPEC-CSS-*` |
| [docs/specs/i18n.md](docs/specs/i18n.md) | 桌面端中英文国际化：后端所有 LLM 提示词和桌面端 UI 文案按照有效语言切换；`app.language=zh\|en\|auto`（默认为 `auto`，读取系统 Locale，可覆盖），并修复写死的中文字面量泄漏 | `SPEC-I18N-*` |
| [docs/specs/long-term-memory.md](docs/specs/long-term-memory.md) | 长期记忆：从会话中自动提炼可长期复用的信息，按照风险分级自动保存或等待用户确认，并提供会话级记忆策略与记忆管理入口 | `SPEC-LTM-*` |

### 归档

| 文档 | 状态 |
|------|------|
| [docs/archive/design-proposals/2026-06-05-desktop-agent-dashboard-design.md](docs/archive/design-proposals/2026-06-05-desktop-agent-dashboard-design.md) | 已由 `desktop-chat-tab.md` 和 `desktop.md` 取代 |

## 阅读规格文档

- 完整约定（结构、ID 方案以及规格与计划的边界）见 [docs/specs/README.md](docs/specs/README.md)。
- 每份规格包含带编号的需求，例如 `SPEC-DSK-TRAY-001`。这些 ID 会出现在各规格末尾的追溯矩阵和提交信息中。
- 追溯矩阵用于将规格 ID 映射到源文件和验证方式。
- 功能规格以 `docs/specs/desktop-chat-tab.md` 为标准格式模板。

## SDD 命令流（斜杠命令）

`.claude/commands/` 提供一套规格驱动开发斜杠命令。该命令流与 spec-kit 的四个阶段对齐，但完全使用本项目的路径与 ID 约定，详见 `.claude/commands/README.md`。

| 命令 | 作用 | 产物 |
|------|------|------|
| `/sdd-spec <名称>` | 新建或精修规格，分配关键字前缀，并登记 SDD 索引 | `docs/specs/<slug>.md` |
| `/sdd-plan <slug>` | 编写实现计划（文件映射与任务） | `docs/superpowers/plans/<日期>-<slug>.md` |
| `/sdd-tasks <slug>` | 将任务细化为带规格 ID 的 `- [ ]` 步骤 | 同一个计划文件 |
| `/sdd-implement <slug> [Task n]` | 按照步骤实现、验证并维护追溯矩阵 | 源代码与追溯矩阵 |

ID 格式为 `SPEC-<域>-<子域>-NNN`，只增不改。规格末尾维护以下三列追溯矩阵：`| 规格 ID | 目标文件/组件 | 验证方式 |`。

## 项目结构

```text
self-analyst/
├── self-analyst-aw/                （ActivityWatch 引擎与 Web UI）
├── self-analyst-content/           （UIA 与 OCR 内容识别）
├── self-analyst-audio/             （音频采集与语音转文字）
├── self-analyst-wiki/              （LLM Wiki 摘要与语义索引）
├── self-analyst-file/              （目录文件监控、摘要与语义索引）
├── self-analyst-app/               （主应用：CLI、桌面端 API、桌面端 UI 前端）
├── self-analyst-desktop/           （Tauri 2.x 桌面端外壳）
├── self-analyst-axsidecar/         （Rust 边车程序）
├── self-analyst-integration-test/  （集成验证 JAR）
├── docs/                           （架构与规格文档）
└── scripts/                        （构建、安装与检查脚本）
```

## 构建、测试与编码约定

- 使用 JDK 21 和 Node.js 20 或更高版本。
- `mvn test`：运行 JUnit 5 测试和桌面端 UI 的 Node 测试套件。
- `mvn -pl self-analyst-app -am -Dtest=ConfigTest -Dsurefire.failIfNoSpecifiedTests=false test`：构建依赖模块并运行一个应用测试类。
- `mvn package -DskipTests`：构建并生成 JAR 包。
- `cd self-analyst-desktop && pnpm install && pnpm tauri dev`：以开发模式运行桌面端外壳。
- `cargo test --manifest-path self-analyst-axsidecar/Cargo.toml`：测试 Rust 边车程序。
- Java 使用四个空格缩进，JavaScript/CSS 使用两个空格缩进。
- 类型使用 `PascalCase`，成员使用 `camelCase`，常量使用 `UPPER_SNAKE_CASE`，包名使用 `com.selfanalyst`。
- Java 测试文件命名为 `*Test.java`，Node 测试文件命名为 `*.test.mjs`；文件系统测试使用 JUnit 5 的 `@TempDir`。
- 修复缺陷时补充回归测试；先运行针对性测试，涉及跨模块变更时再运行 `mvn test`。

## 桌面端 UI 前端

桌面端 UI 位于 `self-analyst-app/src/main/resources/desktop-ui/`。它使用原生 ES5 JavaScript，拆分为 10 个模块，并通过 `<script>` 标签按照以下顺序加载：

```text
utils.js → state.js → api.js → agent.js → chat.js →
chat-drawer.js → config.js → ui.js → events.js → init.js
```

- 无构建步骤、无框架、无打包器。
- 所有模块共享在 `state.js` 中声明的全局 `state` 对象。
- Java 后端 `DesktopServer.java` 通过 `/desktop-ui/*` 从 classpath 提供这些文件。
- `tauri.conf.json` 中 Tauri 的 `frontendDist` 直接指向该目录，不创建同步副本。

## 提交与拉取请求约定

- 提交信息遵循 Conventional Commits 规范，例如 `feat(desktop): ...`、`fix: ...`、`docs: ...` 或 `chore: ...`。
- 适用时，规格驱动的提交应引用规格 ID。
- 所有提交都包含 `Co-Authored-By` 尾注。
- 提交信息使用简体中文，重点说明“为什么”进行变更，并保持每个提交聚焦单一事项。
- 拉取请求应说明行为变化、列出验证方式、关联相关问题或规格，并为 UI 变更附上截图。
- 严禁提交密钥、用户配置、数据库、日志或已被忽略的输出文件。

## 智能体专用说明

保留工作区中与当前任务无关的变更；行为契约发生变化时，应同步更新 `docs/specs/` 中的相关文档。
