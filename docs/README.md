# SelfAnalyst 文档索引

本目录是项目架构、规格驱动开发（SDD）契约和历史设计记录的统一入口。现行行为以
`docs/specs/` 下的正式规格为准；已被取代但仍需保留背景的材料放入 `docs/archive/`。

## 架构

- [architecture.md](architecture.md) — 系统架构、模块边界、数据流、配置和发布结构
- [specs/README.md](specs/README.md) — SDD 文档结构、编号、追溯矩阵和维护约定

## 模块规格

| 文档 | 模块 | 规格前缀 |
|------|------|----------|
| [specs/core.md](specs/core.md) | 主应用、配置、记忆、Agent 与启动流程 | `SPEC-CFG-*`、`SPEC-MEM-*`、`SPEC-AGT-*`、`SPEC-CLI-*` |
| [specs/content.md](specs/content.md) | UIA/OCR 临时识别与上下文标题提取 | `SPEC-CTX-*`、`SPEC-OCR-*`、`SPEC-UIA-*` |
| [specs/audio.md](specs/audio.md) | 音频采集与转写 | `SPEC-AU-*` |
| [specs/file.md](specs/file.md) | 文件监控、提取、摘要与语义索引 | `SPEC-FILE-*` |
| [specs/integration-test.md](specs/integration-test.md) | 跨模块集成验证 | `SPEC-ITEST-*` |

## 功能规格

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
| [specs/long-term-memory.md](specs/long-term-memory.md) | 会话长期记忆提炼和管理 | `SPEC-LTM-*` |

新增或删除正式规格时，必须同步更新本索引。规格正文负责描述当前契约；一次性实施步骤不作为
长期文档保留，必要的历史背景应写入 `docs/archive/` 并明确标注取代关系。

## 归档

- [archive/design-proposals/](archive/design-proposals/) — 已被正式规格取代、但仍保留背景价值的历史设计提案
