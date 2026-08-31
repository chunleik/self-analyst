# SelfAnalyst 文档索引

本目录是项目架构、用户文档和历史设计记录的统一入口。现行行为契约以仓库根目录
`openspec/specs/` 下的 OpenSpec 主规格为准；`docs/specs/` 保留为迁移来源和历史追溯，
不再与 OpenSpec 并行维护。已被取代但仍需保留背景的材料放入 `docs/archive/`。

## 架构

- [architecture.md](architecture.md) — 系统架构、模块边界、数据流、配置和发布结构
- [specs/README.md](specs/README.md) — legacy SDD 文档与 OpenSpec 迁移约定

## OpenSpec 主规格（现行权威）

| Capability | 主规格 | 主要旧文档来源 |
|------------|--------|----------------|
| `accessibility-sidecar` | [spec](../openspec/specs/accessibility-sidecar/spec.md) | `accessibility-sidecar.md` |
| `activitywatch-tools` | [spec](../openspec/specs/activitywatch-tools/spec.md) | `core.md` |
| `agent-context-compaction` | [spec](../openspec/specs/agent-context-compaction/spec.md) | `agent-context-compaction.md` |
| `agent-runtime` | [spec](../openspec/specs/agent-runtime/spec.md) | `core.md` |
| `behavior-advice` | [spec](../openspec/specs/behavior-advice/spec.md) | `behavior-advice.md` |
| `chat-sessions` | [spec](../openspec/specs/chat-sessions/spec.md) | `chat-session-store.md`、`chat-session-sqlite-store.md` |
| `content-event-persistence` | [spec](../openspec/specs/content-event-persistence/spec.md) | `content-title-persistence.md` |
| `desktop-chat` | [spec](../openspec/specs/desktop-chat/spec.md) | `desktop-chat-tab.md` |
| `desktop-shell` | [spec](../openspec/specs/desktop-shell/spec.md) | `desktop.md` |
| `file-metadata-collection` | [spec](../openspec/specs/file-metadata-collection/spec.md) | `file.md` |
| `internationalization` | [spec](../openspec/specs/internationalization/spec.md) | `i18n.md` |
| `llm-budget` | [spec](../openspec/specs/llm-budget/spec.md) | `llm-budget.md` |
| `llm-wiki` | [spec](../openspec/specs/llm-wiki/spec.md) | `llm-wiki.md` |
| `long-term-memory` | [spec](../openspec/specs/long-term-memory/spec.md) | `long-term-memory.md` |
| `title-capture` | [spec](../openspec/specs/title-capture/spec.md) | `content.md` |
| `user-configuration` | [spec](../openspec/specs/user-configuration/spec.md) | `config-toml.md`、`core.md` |
| `user-profile-memory` | [spec](../openspec/specs/user-profile-memory/spec.md) | `core.md` |
| `web-search` | [spec](../openspec/specs/web-search/spec.md) | `web-search.md` |

## Legacy 模块规格（迁移来源）

| 文档 | 模块 | 规格前缀 |
|------|------|----------|
| [specs/core.md](specs/core.md) | 主应用、配置、记忆、Agent 与启动流程 | `SPEC-CFG-*`、`SPEC-MEM-*`、`SPEC-AGT-*`、`SPEC-CLI-*` |
| [specs/content.md](specs/content.md) | UIA 临时读取与上下文标题提取 | `SPEC-CTX-*`、`SPEC-UIA-*`、`SPEC-WCH-*` |
| [specs/file.md](specs/file.md) | 严格无正文的文件系统元数据监控与查询 | `SPEC-FILE-*` |
| [specs/integration-test.md](specs/integration-test.md) | 模块内 JUnit 集成测试与手动 UIA 验证 | `SPEC-ITEST-*` |

## Legacy 功能规格（迁移来源）

| 文档 | 功能 | 规格前缀 |
|------|------|----------|
| [specs/desktop.md](specs/desktop.md) | Tauri 桌面壳、认证、端口握手和后端生命周期 | `SPEC-DSK-*` |
| [specs/desktop-chat-tab.md](specs/desktop-chat-tab.md) | 桌面会话页、消息交互和上下文 | `SPEC-CHAT-TAB-*` |
| [specs/chat-session-store.md](specs/chat-session-store.md) | 会话 REST、数据模型、AgentState 与前端契约 | `SPEC-CSP-*` |
| [specs/chat-session-sqlite-store.md](specs/chat-session-sqlite-store.md) | `chat.db`、WAL、FTS5、迁移和删除恢复 | `SPEC-CSS-*` |
| [specs/agent-context-compaction.md](specs/agent-context-compaction.md) | 长会话上下文压缩 | — |
| [specs/config-toml.md](specs/config-toml.md) | 用户级 `config.toml`、原始文本编辑器和配置迁移 | `SPEC-TOML-*`、`SPEC-CFGUI-*` |
| [specs/i18n.md](specs/i18n.md) | 桌面端中英文国际化 | `SPEC-I18N-*` |
| [specs/llm-budget.md](specs/llm-budget.md) | LLM token 计量与每日预算 | `SPEC-BUDGET-*` |
| [specs/llm-wiki.md](specs/llm-wiki.md) | 多级时间摘要与语义索引 | `SPEC-WIKI-*` |
| [specs/behavior-advice.md](specs/behavior-advice.md) | 行为建议与鼓励卡片 | `SPEC-ADV-*` |
| [specs/web-search.md](specs/web-search.md) | Agent 联网搜索 | `SPEC-WS-*` |
| [specs/accessibility-sidecar.md](specs/accessibility-sidecar.md) | Rust 无障碍树边车与 OS 中性协议 | `SPEC-AXS-*` |
| [specs/content-title-persistence.md](specs/content-title-persistence.md) | UIA 临时读取、上下文标题事件 v2、AW 入库边界与历史净化 | `SPEC-CTP-*` |
| [specs/removed-ocr-audio.md](specs/removed-ocr-audio.md) | OCR 与声音模块移除范围、兼容和恢复说明 | — |
| [specs/long-term-memory.md](specs/long-term-memory.md) | 会话长期记忆提炼和管理 | `SPEC-LTM-*` |

新增、删除或重命名 OpenSpec capability 时，必须同步更新本索引。现行契约只在对应
`openspec/specs/<capability>/spec.md` 维护；一次性实施步骤不进入主规格，必要历史背景应写入
`docs/archive/` 并明确标注取代关系。

## 归档

- [archive/design-proposals/](archive/design-proposals/) — 已被正式规格取代、但仍保留背景价值的历史设计提案
- [archive/removed-features/](archive/removed-features/) — 已移除功能的恢复基线索引
