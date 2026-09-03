# SelfAnalyst 文档索引

本目录是项目架构、用户文档和历史设计记录的统一入口。现行行为契约以仓库根目录
`openspec/specs/` 下的 OpenSpec 主规格为准。已被取代但仍需保留背景的材料放入
`docs/archive/`；已完整迁移且无独立历史价值的旧规格只在 Git 历史中保留。

## 架构

- [architecture.md](architecture.md) — 系统架构、模块边界、数据流、配置和发布结构
- [testing.md](testing.md) — 跨模块测试命令与手动验证指南

## OpenSpec 主规格（现行权威）

| Capability | 主规格 | 历史来源 |
|------------|--------|----------------|
| `accessibility-sidecar` | [spec](../openspec/specs/accessibility-sidecar/spec.md) | [旧文档](archive/legacy-specs/accessibility-sidecar.md) |
| `activitywatch-tools` | [spec](../openspec/specs/activitywatch-tools/spec.md) | [旧文档](archive/legacy-specs/core.md) |
| `agent-context-compaction` | [spec](../openspec/specs/agent-context-compaction/spec.md) | Git 历史 |
| `agent-runtime` | [spec](../openspec/specs/agent-runtime/spec.md) | [旧文档](archive/legacy-specs/core.md) |
| `behavior-advice` | [spec](../openspec/specs/behavior-advice/spec.md) | [旧文档](archive/legacy-specs/behavior-advice.md) |
| `chat-sessions` | [spec](../openspec/specs/chat-sessions/spec.md) | [服务契约](archive/legacy-specs/chat-session-store.md)、[SQLite 规格](archive/legacy-specs/chat-session-sqlite-store.md) |
| `content-event-persistence` | [spec](../openspec/specs/content-event-persistence/spec.md) | Git 历史 |
| `desktop-chat` | [spec](../openspec/specs/desktop-chat/spec.md) | Git 历史 |
| `desktop-shell` | [spec](../openspec/specs/desktop-shell/spec.md) | Git 历史 |
| `file-metadata-collection` | [spec](../openspec/specs/file-metadata-collection/spec.md) | Git 历史 |
| `internationalization` | [spec](../openspec/specs/internationalization/spec.md) | [旧文档](archive/legacy-specs/i18n.md) |
| `llm-budget` | [spec](../openspec/specs/llm-budget/spec.md) | [旧文档](archive/legacy-specs/llm-budget.md) |
| `llm-wiki` | [spec](../openspec/specs/llm-wiki/spec.md) | [旧文档](archive/legacy-specs/llm-wiki.md) |
| `long-term-memory` | [spec](../openspec/specs/long-term-memory/spec.md) | [旧文档](archive/legacy-specs/long-term-memory.md) |
| `raw-event-retention` | [spec](../openspec/specs/raw-event-retention/spec.md) | [归档 change](../openspec/changes/archive/2026-09-04-retain-raw-events-permanently/proposal.md) |
| `title-capture` | [spec](../openspec/specs/title-capture/spec.md) | Git 历史 |
| `user-configuration` | [spec](../openspec/specs/user-configuration/spec.md) | [配置旧文档](archive/legacy-specs/config-toml.md)、[核心旧文档](archive/legacy-specs/core.md) |
| `user-profile-memory` | [spec](../openspec/specs/user-profile-memory/spec.md) | [旧文档](archive/legacy-specs/core.md) |
| `web-search` | [spec](../openspec/specs/web-search/spec.md) | [旧文档](archive/legacy-specs/web-search.md) |

## 已实现的设计参考

- [raw-event-permanent-retention-design.md](raw-event-permanent-retention-design.md) — 合规原始采集事件永久保留、不可变分区与可重建派生层设计

本节保留已实现能力的设计背景与架构取舍，不作为独立行为契约。现行行为以
[`raw-event-retention` 主规格](../openspec/specs/raw-event-retention/spec.md)为准，实施记录见
[归档 change](../openspec/changes/archive/2026-09-04-retain-raw-events-permanently/proposal.md)。

新增、删除或重命名 OpenSpec capability 时，必须同步更新本索引。现行契约只在对应
`openspec/specs/<capability>/spec.md` 维护；一次性实施步骤不进入主规格，必要历史背景应写入
`docs/archive/` 并明确标注取代关系。

## 归档

- [archive/design-proposals/](archive/design-proposals/) — 已被正式规格取代、但仍保留背景价值的历史设计提案
- [archive/legacy-specs/](archive/legacy-specs/) — 未逐项纳入主规格的旧实施决策、测试条目和稳定 ID
- [archive/removed-features/](archive/removed-features/) — 已移除功能的恢复基线索引
