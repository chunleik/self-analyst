# SelfAnalyst 文档索引

本目录是项目架构、用户文档和历史设计记录的统一入口。现行行为契约以仓库根目录
`openspec/specs/` 下的 OpenSpec 主规格为准。已被取代但仍需保留背景的材料放入
`docs/archive/`；已完整迁移且无独立历史价值的旧规格只在 Git 历史中保留。

## 与 ActivityWatch 的关系

SelfAnalyst 不是 [ActivityWatch](https://activitywatch.net) 的分支、发行版或包装器，仓库与默认
发布包也不包含 ActivityWatch 源代码或完整 `aw-webui`。`self-analyst-events` 是独立的 Java 实现：
数据格式和 HTTP API（`/api/0` 与 aw-client 使用的 `/0`）兼容 ActivityWatch（MPL-2.0），以便沿用
其 bucket、heartbeat 合并和 AQL 时间线模型。许可证与兼容性说明见
[THIRD-PARTY-NOTICES.md](../THIRD-PARTY-NOTICES.md)。

在本项目中，ActivityWatch 兼容层承担**本地活动时间线与查询协议**：窗口、AFK、上下文标题和
文件元数据等采集结果进入兼容存储；Wiki 聚合与 Agent 查询都消费这条时间线，而不是直接读采集库。
SelfAnalyst 在此之上增加标题采集、文件元数据、嵌入式原始事件永久层、Wiki 与桌面 Agent，并不
复刻官方桌面客户端，也不替代官方 ActivityWatch 发行版。

| 模式 | 配置 | 本项目做什么 | 不做 / 不保证什么 |
|------|------|--------------|-------------------|
| `embedded`（默认） | `aw.mode=embedded`，端口由 `aw.port` 决定（默认 `5700`） | 进程内启动兼容服务与窗口/AFK watcher；合规事件先写入月度 raw SQLite，再投影为可重建的 `events.db` | 不是官方 `aw-server`；不随包分发官方 Web UI |
| `external` | `aw.mode=external`，查询 `aw.base-url`（默认 `http://localhost:5600/api/0`） | 不启动内嵌服务；Agent 通过兼容 HTTP API 访问已有 ActivityWatch | 不控制外部数据目录；[原始事件永久保留](../openspec/specs/raw-event-retention/spec.md) 不可用，也不得声称外部事件已被本项目永久层保存 |

兼容边界：

- **兼容并继续使用的协议面**：`info`、buckets、events、heartbeat、AQL、settings、export/import。
  Agent 只通过 [EventQueryTools](../openspec/specs/event-query-tools/spec.md) 走 HTTP，不直连数据库。
- **本项目扩展，不属于官方 ActivityWatch**：内容事件 v2 标题策略、受桌面认证保护的原始事件查询、
  桌面 API、Wiki 摘要投影与文件元数据采集。这些能力只在嵌入式控制的写入链路上完整成立。
- **查询语义**：现有 events / AQL / Agent 工具读取 heartbeat 合并后的投影；逐条原始 heartbeat 只经
  受保护桌面 API 提供。

数据流与模块边界见 [architecture.md](architecture.md)；内容字段与嵌入式启动顺序见
[content-event-persistence](../openspec/specs/content-event-persistence/spec.md)。

## 架构

- [architecture.md](architecture.md) — 系统架构、模块边界、数据流、配置和发布结构
- [testing.md](testing.md) — 跨模块测试命令与手动验证指南

## OpenSpec 主规格（现行权威）

| Capability | 主规格 | 历史来源 |
|------------|--------|----------------|
| `accessibility-sidecar` | [spec](../openspec/specs/accessibility-sidecar/spec.md) | [旧文档](archive/legacy-specs/accessibility-sidecar.md) |
| `agent-context-compaction` | [spec](../openspec/specs/agent-context-compaction/spec.md) | Git 历史 |
| `agent-runtime` | [spec](../openspec/specs/agent-runtime/spec.md) | [旧文档](archive/legacy-specs/core.md) |
| `behavior-advice` | [spec](../openspec/specs/behavior-advice/spec.md) | [旧文档](archive/legacy-specs/behavior-advice.md) |
| `chat-sessions` | [spec](../openspec/specs/chat-sessions/spec.md) | [服务契约](archive/legacy-specs/chat-session-store.md)、[SQLite 规格](archive/legacy-specs/chat-session-sqlite-store.md) |
| `content-event-persistence` | [spec](../openspec/specs/content-event-persistence/spec.md) | Git 历史 |
| `desktop-chat` | [spec](../openspec/specs/desktop-chat/spec.md) | Git 历史 |
| `desktop-shell` | [spec](../openspec/specs/desktop-shell/spec.md) | Git 历史 |
| `event-query-tools` | [spec](../openspec/specs/event-query-tools/spec.md) | [旧文档](archive/legacy-specs/core.md) |
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

新增、删除或重命名 OpenSpec capability 时，必须同步更新本索引。现行契约只在对应
`openspec/specs/<capability>/spec.md` 维护；一次性实施步骤不进入主规格，必要历史背景应写入
`docs/archive/` 并明确标注取代关系。

## 归档

- [archive/design-proposals/](archive/design-proposals/) — 已被正式规格取代、但仍保留背景价值的历史设计提案，包括[原始事件永久保留设计](archive/design-proposals/2026-08-30-raw-event-permanent-retention-design.md)
- [archive/legacy-specs/](archive/legacy-specs/) — 未逐项纳入主规格的旧实施决策、测试条目和稳定 ID
- [archive/removed-features/](archive/removed-features/) — 已移除功能的恢复基线索引
