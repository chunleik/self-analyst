# SelfAnalyst 会话存储收敛为 SQLite 单库 SDD 规格说明书

> Specification-Driven Development spec. 本文档定义桌面端会话存储从「分片文件 + SQLite 投影 + index.state/tombstone 恢复机制」收敛为 **SQLite 单库（WAL）唯一权威 + FTS5 搜索** 的行为契约。实现必须可追溯至本文档中的规格 ID。

---

## 1. 文档元信息

| 属性 | 值 |
|------|-----|
| 功能名称 | 聊天会话存储收敛为 SQLite 单库（WAL + FTS5），替代分片/投影/恢复机制 |
| 文档状态 | 已实现并验证 |
| 日期 | 2026-08-28 |
| 目标平台 | SelfAnalyst 桌面后端，Windows 优先 |
| 规格前缀 | `SPEC-CSS-*`（Chat Session Sqlite） |
| 主要后端逻辑 | `self-analyst-app/src/main/java/com/selfanalyst/desktop/store/ChatSessionStore.java`（重写内部实现，类名与 public API 不变） |
| 被取代文件 | `SqliteChatSessionIndex.java`、`ChatSessionIndex.java`、`ChatSessionStoreIo.java`（删除） |

---

## 2. 背景与动机

`chat-session-store.md`（`SPEC-CSP-*`）确立了「分片为正文权威 + `index.db` 为派生投影」的双存储架构。为维持两份存储的一致性，实现被迫引入一整套自研崩溃恢复机制：`index.state`（CLEAN/DIRTY intent）、`index.db.ready` 迁移标记、`delete-<id>.state` 删除 tombstone、投影损坏重建、临时文件清理。这部分机制约占 `ChatSessionStore`（1640 行）的一半，并对应 `SPEC-CSP-TST-035..052` 等大量恢复语义测试。

SQLite 在 WAL 模式下本身即提供：单文件事务（原子提交/回滚）、崩溃后自动恢复（WAL replay/checkpoint）、单 writer 串行写。把会话正文与索引放进**同一数据库的同一事务**，可以从根上删除「双存储一致性」问题及其全部恢复机制——用成熟开源实现（SQLite/FTS5）替换自研一致性代码。

REST 接口契约（`SPEC-CSP-API-001..012`）、前端契约（`SPEC-CSP-FE-*`）、数据模型字段与裁剪不变量（`SPEC-CSP-DEC-005/-013/-014`）**全部保持不变**；本 spec 只替换存储内部实现。

---

## 3. 与既有 spec 的关系（取代说明）

- **SPEC-CSS-DEC-001**：本 spec 取代 `chat-session-store.md` 中以下存储内部决策，其余条款继续有效：
  - `SPEC-CSP-DEC-002` 中「shard 为正文唯一事实来源」改为「`chat.db` 为唯一事实来源」；AgentState 为模型历史权威的条款不变。
  - `SPEC-CSP-DEC-008`（分片布局）、`SPEC-CSP-DEC-015`（跨文件恢复）、`SPEC-CSP-DEC-016`（SQLite 有界索引，升级为正文+索引同库）、`SPEC-CSP-DEC-017`（跨存储删除 saga 改为库内 pending_deletions 表，见 §4）整体取代。
  - `SPEC-CSP-DEC-018`（单生产 writer）保留：`.writer.lock` 进程级独占 lease 语义不变。
  - `SPEC-CSP-API-010`（原子写/跨文件恢复/分片隔离）由 SQLite 事务语义整体取代，见 `SPEC-CSS-API-001`。
  - `SPEC-CSP-TST-014/-035..037/-040/-042/-044..047/-049/-052`（恢复机制专项测试）随机制删除而废止，由 `SPEC-CSS-TST-*` 覆盖等价行为。

---

## 4. 设计结论（决策与取舍）

- **SPEC-CSS-DEC-002**（单库单事务）：所有会话正文与列表/搜索元数据存于 `{memoryDir}/chat-sessions/chat.db`（WAL 模式、`synchronous=FULL`、外键开启）。每次 mutation 在**一个 SQLite 事务**内完成：UPSERT sessions 行 + 重写该会话 messages 行（单会话 ≤200 条，重写成本有界）+ 更新 active 指针/generation。commit 即唯一 commit point；崩溃后由 WAL 恢复，无应用层 DIRTY 状态。
  - *取舍*：放弃「写消息只触及该会话分片」的文件级隔离；换来单事务原子性。单会话消息重写 ≤200 行，成本与重写一个 ≤200 条消息的 shard 相当。

- **SPEC-CSS-DEC-003**（类名与 API 不变）：`ChatSessionStore` 类名、包名与全部 public 方法签名（`openExclusive/listIndex/listIndexPage/getSession/create/updateMeta/updateMemoryPolicy/delete/appendMessages/updateMessage/setActiveSession/writeSummary/beginDeletion/pendingDeletionIds/deletePendingTranscript/finishDeletion/close` 及模型类 `Session/Message/SessionMeta/Index/IndexPage/CreateRequest/DeleteResult`）保持不变。Controller、`ChatSessionDeletionCoordinator`、`ChatSummaryService`、`DesktopAgentController`、前端均不改动。

- **SPEC-CSS-DEC-004**（删除 saga 收敛为库内表）：删除 intent 由文件 tombstone 改为 `pending_deletions(session_id PK, requested_at)` 表。`beginDeletion` = 单行 UPSERT（durable intent）；`deletePendingTranscript` = 单事务 DELETE（ON DELETE CASCADE 清 messages）+ active 指针重选；`finishDeletion` = 删 intent 行。启动时 `deletionIntentIdsForRecovery` 读取该表，由 `ChatSessionDeletionCoordinator` 幂等补齐 AgentState 删除（协调器逻辑不变）。语义与 `SPEC-CSP-DEC-017` 等价，证据从文件变为表行。

- **SPEC-CSS-DEC-005**（FTS5 trigram 搜索 + LIKE 回退）：会话搜索仍在 **title + summary + lastMessagePreview** 三个字段上进行（`SPEC-CSP-DEC-009` 不变）。新建 `sessions_fts`（FTS5，external content，`tokenize='trigram case_sensitive 0'`），由 `sessions` 表 AFTER INSERT/UPDATE/DELETE 触发器自动维护，与业务写同事务。查询规范化后：code point 数 ≥ 3 走 `MATCH '"<转义后查询>"'`（trigram 支持中日韩子串匹配）；< 3 或 FTS 不可用/报错时回退转义 `LIKE`（`%`/`_` 按字面量，`SPEC-CSP-TST-051` 语义保留）。
  - *取舍*：trigram 对中文子串友好且大小写不敏感；3 字符以下查询仍走 LIKE 全表扫元数据（会话表仅元数据，代价有界）。

- **SPEC-CSS-DEC-006**（存量数据迁移）：打开时若 `chat.db` 不存在而目录中存在旧分片（`<hex32>.json`）或旧 `index.db`/`index.json`：先构建 `chat.db.migrating`——单事务读入全部有效 shard（id 与文件名一致且可解析者）+ 从旧 `index.db`（可读时）或 shard 推断 active 指针——完成后原子 rename 为 `chat.db`，再把旧存储文件整体移入 `chat-sessions/legacy/` 备份目录（不删除）。迁移中途崩溃：`.migrating` 残留文件在下一次打开时删除重做。`metadata.schema_version=2` 是迁移完成标记。
  - *取舍*：保留只读备份而非就地删除，迁移可人工回滚；备份目录不参与任何读写。

- **SPEC-CSS-DEC-007**（不变量原样保留）：消息/会话裁剪（200 条、20000 code point、辅助字段上限、opaque 预算、完整 turn 淘汰、单会话 16 MiB 序列化预算）、ID 生成与校验（hex32/hex12）、状态机（pending/sent/error 流转）、generation 单调递增（active-only 不递增）、keyset 游标绑定 query+generation——全部沿用现有实现代码与语义（`SPEC-CSP-API-009`、`SPEC-CSP-TST-038/-041/-050` 等继续有效）。

---

## 5. 数据模型（库内 schema）

```sql
PRAGMA journal_mode=WAL; PRAGMA synchronous=FULL; PRAGMA foreign_keys=ON;

CREATE TABLE IF NOT EXISTS metadata(
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL
);  -- schema_version=2, active_session_id, generation

CREATE TABLE IF NOT EXISTS sessions(
  id TEXT PRIMARY KEY NOT NULL CHECK(length(id) = 32),
  title TEXT,
  created_at TEXT,                   -- 应用写入 ISO-8601
  updated_at TEXT,
  source TEXT,
  context_label TEXT,
  context_snapshot TEXT,             -- canonical JSON，≤64KiB
  memory_policy TEXT,
  summary TEXT,
  last_message_preview TEXT,
  message_count INTEGER NOT NULL DEFAULT 0 CHECK(message_count >= 0)
);

CREATE TABLE IF NOT EXISTS messages(
  id TEXT PRIMARY KEY NOT NULL,      -- 应用校验 hex12
  session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
  seq INTEGER NOT NULL,
  role TEXT NOT NULL,
  content TEXT,
  created_at TEXT,
  status TEXT,
  error TEXT,
  context_snapshot TEXT,
  suggested_tasks TEXT,
  UNIQUE(session_id, seq)
);

CREATE VIRTUAL TABLE IF NOT EXISTS sessions_fts USING fts5(
  title, summary, last_message_preview,
  content='sessions', content_rowid='rowid',
  tokenize='trigram case_sensitive 0'
);
-- AFTER INSERT/UPDATE/DELETE 触发器维护 sessions_fts（SQLite 官方 external-content 范式）

CREATE TABLE IF NOT EXISTS pending_deletions(
  session_id TEXT PRIMARY KEY NOT NULL,
  requested_at TEXT NOT NULL
);
```

- 列表排序键：`updated_at DESC, id DESC`（与 `META_ORDER` 一致；ISO-8601 字符串字典序即时间序，同刻时以 id 决胜）。
- `Session`/`Message` 的 JSON 序列化形状（REST 契约）不变；`createdAt/updatedAt` 以 ISO-8601 字符串存取，与 Jackson `Instant` 映射一致。
- `title/created_at/updated_at/source/memory_policy` 等非空要求由应用写入和规范化逻辑保证，物理
  schema 为兼容旧数据保留 nullable；上面的 SQL 与当前 `initializeSchema` DDL 一致。

---

## 6. 行为契约

### SPEC-CSS-API-001：原子性与崩溃恢复

- 任一 mutation 的全部库内变更在**一个事务**中提交；事务提交前进程崩溃 → WAL 自动回滚，库保持 mutation 前状态；提交后崩溃 → 数据完整可见。不存在应用层 DIRTY/CLEAN 状态文件。
- `.writer.lock` lease 取得失败仍 fail fast（`SPEC-CSP-DEC-018` 不变）；第二个生产实例不得打开同一 `chat.db` 进行写入。
- `chat.db` 损坏（无法打开/schema 校验失败）：备份为 `chat.db.corrupt-<时间戳>` 后，若 `legacy/` 备份存在则从 legacy shards 重建，否则以空库启动并记录 error；不得静默丢数据。

### SPEC-CSS-API-002：功能等价

- §3 未取代的全部 `SPEC-CSP-API-*` 行为逐一等价：列表/分页/搜索/单会话读取/创建/元信息更新/删除/active 指针/追加消息/更新消息/摘要写回/裁剪不变量/ID 与状态机校验。除存储介质外，调用方不可感知。

### SPEC-CSS-API-003：迁移（SPEC-CSS-DEC-006）

- 迁移后：`chat.db` 含全部旧会话正文与元数据、active 指针与最新者一致；`legacy/` 含全部旧文件；重复打开不重复迁移；迁移后 mutation 不再触碰 `legacy/`。
- 旧 `delete-<id>.state` tombstone 存在时：迁移把对应 id 写入 `pending_deletions`，由既有恢复流程补齐删除。

### SPEC-CSS-API-004：搜索（SPEC-CSS-DEC-005）

- ≥3 code point 查询走 FTS5 trigram MATCH；命中集合与 LIKE 子串匹配一致（含中文、大小写不敏感）。
- <3 code point、`%`/`_` 字面量、FTS 报错时回退 LIKE；排序与分页与无搜索路径一致（`updated_at DESC, id DESC` keyset）。

---

## 7. 非目标

- **SPEC-CSS-NON-001**：不改变任何 REST/前端契约、AgentState 存储、消息正文全文检索（仍不索引消息正文，`SPEC-CSP-NON-004` 的搜索范围约束不变）。
- **SPEC-CSS-NON-002**：不迁移 `localStorage` 旧数据（`SPEC-CSP-DEC-004` 不变）。
- **SPEC-CSS-NON-003**：不为其它存储（tasks、config、wiki）换库；本 spec 仅限 chat-sessions。

---

## 8. 测试规格

| 规格 ID | 测试 | 预期 |
|---------|------|------|
| SPEC-CSS-TST-001 | 空目录打开 | 自动建库建表；`GET /sessions` 等价空列表行为 |
| SPEC-CSS-TST-002..013 | 沿用 `SPEC-CSP-TST-001..013` 的 CRUD/分页/裁剪/404/400 用例（存储换库） | 全部通过，行为不变 |
| SPEC-CSS-TST-014 | mutation 期间模拟崩溃（事务未提交） | 库保持 mutation 前状态；无 DIRTY 文件 |
| SPEC-CSS-TST-015 | 旧分片 + 旧 index.db 迁移 | 会话正文/元数据/active 完整入库；旧文件移入 `legacy/`；二次打开不重复迁移 |
| SPEC-CSS-TST-016 | 旧 `delete-<id>.state` 迁移 | id 进入 `pending_deletions`，恢复流程补齐删除 |
| SPEC-CSS-TST-017 | FTS 中文/英文子串搜索（≥3 字符） | 命中与 LIKE 一致；大小写不敏感 |
| SPEC-CSS-TST-018 | 1..2 字符查询与 `%`/`_` 字面量 | 走 LIKE 回退，按字面量匹配 |
| SPEC-CSS-TST-019 | `chat.db` 损坏且存在 `legacy/` | 备份损坏库并从 legacy 重建；无 legacy 时 error 日志 + 空库启动 |
| SPEC-CSS-TST-020 | 双 writer | 第二个 `openExclusive` fail fast（语义不变） |
| SPEC-CSS-TST-021 | 删除 saga | begin→transcript delete→finish 语义与 `SPEC-CSP-TST-044..046` 等价（表行替代文件证据） |

---

## 规格追溯矩阵

| 规格 ID | 对应文件/组件 | 验证方式 |
|---------|--------------|---------|
| SPEC-CSS-DEC-001..007 | 本 spec | 代码审查 |
| SPEC-CSS-API-001 | `ChatSessionStore.java`（事务边界、WAL、lease） | 单元测试、代码审查 |
| SPEC-CSS-API-002 | `ChatSessionStore.java` | 既有行为测试（改写后） |
| SPEC-CSS-API-003 | `ChatSessionStore.java`（迁移） | 单元测试 |
| SPEC-CSS-API-004 | `ChatSessionStore.java`（FTS/LIKE） | 单元测试 |
| SPEC-CSS-TST-001..021 | `ChatSessionStoreTest.java`（改写）、`ChatSessionMigrationTest.java`（新增） | 单元测试 |
