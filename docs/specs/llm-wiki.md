# SelfAnalyst LLM Wiki SDD 规格说明书

> **迁移状态：** 现行行为契约已迁移至 [`llm-wiki`](../../openspec/specs/llm-wiki/spec.md)。本文档仅保留为旧 ID、历史背景和源码追溯，不再独立维护。

> Specification-Driven Development — 本文档定义 LLM Wiki 多级时间摘要与语义索引机制的精确行为契约。实现必须可追溯至本文档中的规格 ID。

---

## 1. 文档元信息

| 属性 | 值 |
|------|-----|
| 功能名称 | LLM Wiki 多级时间摘要与语义索引 |
| 文档状态 | 已实现（当前契约） |
| 日期 | 2026-06-08 |
| 目标模块 | `self-analyst-wiki` |
| 主要包 | `com.selfanalyst.wiki` |
| 数据库 | `{memory.dir}/llm-wiki.db` |
| 向量索引 | `{memory.dir}/wiki-semantic-index/` |
| 语义索引元数据表 | `wiki_semantic_documents` |
| 默认 embedding model | `text-embedding-3-small` |
| 默认 embedding 维度 | `1024` |
| Agent 入口 | `SelfAnalystAgent` 注册 `WikiTools` |
| 主要用户入口 | Agent 对话中的时间段复盘、任务回顾、趋势分析、模糊主题检索 |

---

## 2. 实施前背景与缺口

实施 LLM Wiki 前，SelfAnalyst 已经具备以下基础能力：

- `self-analyst-aw`: 持久采集窗口、AFK 等 ActivityWatch 事件。
- `self-analyst-content`: 以 `aw-watcher-content_{hostname}` bucket 记录不含原始正文的上下文标题事件。
- `SummaryService`: 临时查询窗口和 AFK 事件，生成当前状态与时间线摘要。
- `SelfAnalystAgent`: 使用长期记忆 `memory.json` 和 ActivityWatch 工具回答用户问题。
- `MemoryStore`: 只保存目标、行为模式、改进记录，不适合保存永久时间线摘要。

当时的缺口：

- 没有小时、半天、天、周、双周、月级别的长期任务摘要。
- Agent 每次分析历史时间段时需要重新查原始事件，成本高且上下文不可复用。
- 缺少后台补算、失败重试、断点续跑机制。
- 缺少隐私边界明确的长期摘要存储。
- 缺少按自然语言主题跨时间段检索 Wiki 摘要的能力。

LLM Wiki 的职责是把原始 ActivityWatch 事件沉淀为多级、可查询、可追溯、可重试的本地时间摘要库，并为已生成摘要提供本地语义索引。

---

## 3. 目标

- **SPEC-WIKI-GOAL-001**: 系统必须自动生成并永久保存多级时间摘要，层级包括 `HOUR`, `HALF_DAY`, `DAY`, `WEEK`, `BIWEEK`, `MONTH`。
- **SPEC-WIKI-GOAL-002**: `HOUR`, `HALF_DAY`, `DAY` 必须直接从原始 AW 事件生成，不得由下级摘要拼接生成。
- **SPEC-WIKI-GOAL-003**: `WEEK`, `BIWEEK`, `MONTH` 必须由已完成的摘要汇总生成，不得重新读取原始屏幕文本。
- **SPEC-WIKI-GOAL-004**: Agent 必须能按用户指定时间段查询 Wiki 摘要，用于回答任务复盘和趋势分析问题。
- **SPEC-WIKI-GOAL-005**: 启动后必须低速后台补算已有历史，不阻塞应用启动。
- **SPEC-WIKI-GOAL-006**: LLM 不可用时不得写入低质量规则兜底摘要，必须保留可重试状态。
- **SPEC-WIKI-GOAL-007**: Wiki 数据库不得长期保存完整 OCR/UIA 原文。
- **SPEC-WIKI-GOAL-008**: 系统必须支持对 Wiki 摘要和任务片段进行本地语义检索，用于回答模糊主题问题。

---

## 4. 非目标

- **SPEC-WIKI-NON-001**: 第一版不新增桌面 Wiki 浏览页。
- **SPEC-WIKI-NON-003**: 第一版不把 Wiki 摘要写回 ActivityWatch bucket。
- **SPEC-WIKI-NON-004**: 第一版不自动删除历史摘要，默认永久保留。
- **SPEC-WIKI-NON-005**: 第一版不提供手动编辑 Wiki 摘要功能。
- **SPEC-WIKI-NON-006**: 第一版不要求跨设备同步、账号系统或云端存储。

---

## 5. 架构契约

### 5.1 模块边界

新增包结构:

```text
com.selfanalyst.wiki
├── WikiLevel.java
├── WikiStatus.java
├── WikiEntry.java
├── WikiStore.java
├── WikiPeriod.java
├── WikiPeriodFactory.java
├── WikiFactBuilder.java
├── WikiSummarizer.java
├── WikiWorker.java
├── EmbeddingClient.java
├── OpenAiCompatibleEmbeddingClient.java
├── WikiSemanticIndex.java
├── WikiEmbeddingWorker.java
└── WikiTools.java
```

- **SPEC-WIKI-ARCH-001**: `WikiStore` 是唯一访问 `{memory.dir}/llm-wiki.db` 的类。
- **SPEC-WIKI-ARCH-002**: `WikiWorker` 只能通过 `WikiStore` 读写 Wiki 状态，只能通过 `EventStore` 读取原始 AW 事件。
- **SPEC-WIKI-ARCH-003**: `WikiSummarizer` 只负责把输入 facts 转换为结构化摘要，不直接访问数据库。
- **SPEC-WIKI-ARCH-004**: `WikiTools` 是 Agent 查询 Wiki 的唯一工具入口。
- **SPEC-WIKI-ARCH-005**: `SelfAnalystAgent` 必须注册 `WikiTools`，不得直接访问 `WikiStore`。
- **SPEC-WIKI-ARCH-006**: `AppSession` 负责创建、启动、关闭 `WikiWorker`。
- **SPEC-WIKI-ARCH-007**: 所有依赖必须通过构造器注入，不得使用静态单例。
- **SPEC-WIKI-SEM-ARCH-001**: `llm-wiki.db` 继续保存业务摘要、状态和语义索引元数据；Lucene 目录只保存可重建的检索索引。
- **SPEC-WIKI-SEM-ARCH-002**: 系统必须新增 `EmbeddingClient` 接口和 `OpenAiCompatibleEmbeddingClient` 实现。
- **SPEC-WIKI-SEM-ARCH-003**: 系统必须新增 `WikiSemanticIndex`，封装 Lucene 写入、删除、重建和 KNN 查询。
- **SPEC-WIKI-SEM-ARCH-004**: 系统必须新增 `WikiEmbeddingWorker`，异步处理待 embedding 的 Wiki 文档。
- **SPEC-WIKI-SEM-ARCH-005**: `WikiTools` 必须新增 `semanticSearchWiki(...)`，Agent 不得直接访问 Lucene。
- **SPEC-WIKI-SEM-ARCH-006**: 摘要生成失败不得阻塞语义索引其他任务；embedding 失败不得回滚已生成摘要。

### 5.2 数据流

```text
AW raw buckets
  ├─ aw-watcher-window_{hostname}
  ├─ aw-watcher-afk_{hostname}
  └─ aw-watcher-content_{hostname}
          │
          ▼
WikiFactBuilder
          │
          ├─ HOUR / HALF_DAY / DAY facts from raw events
          │
          ▼
WikiSummarizer ── LLM plain completion
          │
          ▼
WikiStore (llm-wiki.db)
          │
          ├─ WEEK from DAY summaries
          ├─ BIWEEK from WEEK summaries
          └─ MONTH from DAY summaries
          │
          ├─ wiki_semantic_documents metadata
          │        │
          │        ▼
          │   WikiEmbeddingWorker ── EmbeddingClient
          │        │
          │        ▼
          │   WikiSemanticIndex (Lucene vector index)
          │
          ▼
WikiTools → SelfAnalystAgent
```

- **SPEC-WIKI-FLOW-001**: `HOUR`, `HALF_DAY`, `DAY` 生成输入必须来自原始事件。
- **SPEC-WIKI-FLOW-002**: `WEEK` 生成输入必须来自 `DAY` 摘要。
- **SPEC-WIKI-FLOW-003**: `BIWEEK` 生成输入必须来自 `WEEK` 摘要。
- **SPEC-WIKI-FLOW-004**: `MONTH` 生成输入必须来自同一自然月内的 `DAY` 摘要。
- **SPEC-WIKI-SEM-FLOW-001**: `SUMMARIZED` entry 写入后必须为 entry summary 和 task segments 生成语义索引元数据。
- **SPEC-WIKI-SEM-FLOW-002**: `WikiEmbeddingWorker` 必须读取 `PENDING` 语义文档，生成 embedding 后写入 Lucene，并把元数据状态更新为 `INDEXED`。
- **SPEC-WIKI-SEM-FLOW-003**: `semanticSearchWiki` 必须先为查询生成 embedding，再通过 `WikiSemanticIndex` 执行向量检索和过滤。
- **SPEC-WIKI-SEM-FLOW-004**: 语义索引不得读取、保存或检索原始 OCR/UIA `text_content`。

---

## 6. 时间层级规格

### 6.1 通用时间规则

- **SPEC-WIKI-TIME-001**: 所有时间边界使用系统默认时区 `ZoneId.systemDefault()`。
- **SPEC-WIKI-TIME-002**: 每条记录必须保存 `timezone`，值为生成时的 `ZoneId` 字符串。
- **SPEC-WIKI-TIME-003**: `period_start` 和 `period_end` 使用 ISO-8601 instant 字符串保存。
- **SPEC-WIKI-TIME-004**: `period_start` 是闭区间起点，`period_end` 是开区间终点。
- **SPEC-WIKI-TIME-005**: 当前未结束的时间块不得生成摘要。
- **SPEC-WIKI-TIME-006**: 如果一个时间块没有任何可用原始事件，允许标记为 `SKIPPED`，并记录原因。

### 6.2 层级定义

| Level | 边界 | 生成输入 | 说明 |
|-------|------|----------|------|
| `HOUR` | 本地完整小时 | 原始 AW 事件 | 例如 `10:00-11:00` |
| `HALF_DAY` | `00:00-12:00`, `12:00-24:00` | 原始 AW 事件 | 不由小时摘要生成 |
| `DAY` | 本地自然日 | 原始 AW 事件 | 不由半天摘要生成 |
| `WEEK` | ISO 周一到下周一 | `DAY` 摘要 | 周趋势 |
| `BIWEEK` | 两个连续 ISO 周 | `WEEK` 摘要 | 双周趋势 |
| `MONTH` | 自然月 | `DAY` 摘要 | 月趋势 |

- **SPEC-WIKI-TIME-007**: `HOUR` 必须对齐到整点。
- **SPEC-WIKI-TIME-008**: `HALF_DAY` 只能是本地日期的上半天或下半天。
- **SPEC-WIKI-TIME-009**: `DAY` 必须从本地 `00:00` 到下一天 `00:00`。
- **SPEC-WIKI-TIME-010**: `WEEK` 必须使用 ISO-8601 周定义，周一为第一天。
- **SPEC-WIKI-TIME-011**: `BIWEEK` 必须由两个连续 ISO 周构成，起点为第一个周的周一。
- **SPEC-WIKI-TIME-012**: `MONTH` 必须使用自然月边界，不得使用 30 天滚动窗口。

---

## 7. 数据库规格

### 7.1 数据库位置

- **SPEC-WIKI-DB-001**: 数据库文件固定为 `{memory.dir}/llm-wiki.db`。
- **SPEC-WIKI-DB-002**: `WikiStore` 初始化时必须创建父目录。
- **SPEC-WIKI-DB-003**: 数据库 schema 版本使用 `PRAGMA user_version`，第一版为 `1`。

### 7.2 `wiki_entries` 表

```sql
CREATE TABLE IF NOT EXISTS wiki_entries (
  id TEXT PRIMARY KEY,
  level TEXT NOT NULL,
  period_start TEXT NOT NULL,
  period_end TEXT NOT NULL,
  timezone TEXT NOT NULL,
  status TEXT NOT NULL,
  summary TEXT,
  primary_task TEXT,
  task_segments_json TEXT,
  metrics_json TEXT,
  source_entry_ids_json TEXT,
  model TEXT,
  prompt_version TEXT,
  retry_count INTEGER NOT NULL DEFAULT 0,
  next_retry_at TEXT,
  last_error TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  summarized_at TEXT,
  UNIQUE(level, period_start, period_end, timezone)
);
```

必需索引:

```sql
CREATE INDEX IF NOT EXISTS idx_wiki_entries_period
  ON wiki_entries(level, period_start, period_end);

CREATE INDEX IF NOT EXISTS idx_wiki_entries_status_retry
  ON wiki_entries(status, next_retry_at);
```

- **SPEC-WIKI-DB-004**: `level` 必须是 `WikiLevel` 中的合法值。
- **SPEC-WIKI-DB-005**: `status` 必须是 `WikiStatus` 中的合法值。
- **SPEC-WIKI-DB-006**: `(level, period_start, period_end, timezone)` 必须唯一。
- **SPEC-WIKI-DB-007**: 写入摘要时必须在单个事务内更新 `status`, `summary`, `metrics_json`, `updated_at`, `summarized_at`。
- **SPEC-WIKI-DB-008**: `task_segments_json`, `metrics_json`, `source_entry_ids_json` 必须是合法 JSON 字符串或 null。

### 7.3 状态枚举

```java
enum WikiStatus {
    PENDING,
    SUMMARIZED,
    FAILED,
    SKIPPED
}
```

- **SPEC-WIKI-STAT-001**: `PENDING` 表示等待生成或依赖未满足。
- **SPEC-WIKI-STAT-002**: `SUMMARIZED` 表示摘要成功生成。
- **SPEC-WIKI-STAT-003**: `FAILED` 表示生成失败且等待重试。
- **SPEC-WIKI-STAT-004**: `SKIPPED` 表示该时间块无可用数据或不应生成。

### 7.4 `wiki_semantic_documents` 表

语义索引元数据必须保存在同一个 `llm-wiki.db` 文件中。新增该表后，数据库 schema version 必须升级到 `2`。

```sql
CREATE TABLE IF NOT EXISTS wiki_semantic_documents (
  doc_id TEXT PRIMARY KEY,
  entry_id TEXT NOT NULL,
  doc_type TEXT NOT NULL,
  level TEXT NOT NULL,
  period_start TEXT NOT NULL,
  period_end TEXT NOT NULL,
  text_hash TEXT NOT NULL,
  embedding_model TEXT NOT NULL,
  embedding_dimensions INTEGER NOT NULL,
  status TEXT NOT NULL,
  retry_count INTEGER NOT NULL DEFAULT 0,
  next_retry_at TEXT,
  last_error TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  indexed_at TEXT,
  UNIQUE(entry_id, doc_type, text_hash, embedding_model, embedding_dimensions)
);
```

必需索引:

```sql
CREATE INDEX IF NOT EXISTS idx_wiki_semantic_documents_status_retry
  ON wiki_semantic_documents(status, next_retry_at);

CREATE INDEX IF NOT EXISTS idx_wiki_semantic_documents_entry
  ON wiki_semantic_documents(entry_id, doc_type);
```

- **SPEC-WIKI-DB-009**: 新增 `wiki_semantic_documents` 表后，`PRAGMA user_version` 必须为 `2`。
- **SPEC-WIKI-SEM-DB-001**: `doc_type` 合法值为 `ENTRY_SUMMARY | TASK_SEGMENT`。
- **SPEC-WIKI-SEM-DB-002**: `status` 合法值为 `PENDING | INDEXED | FAILED | STALE`。
- **SPEC-WIKI-SEM-DB-003**: `text_hash` 必须使用规范化索引文本计算，文本变化必须产生新 hash。
- **SPEC-WIKI-SEM-DB-004**: embedding model 或 dimensions 变化时，旧文档必须标记为 `STALE` 并等待重建。
- **SPEC-WIKI-SEM-DB-005**: `wiki_semantic_documents` 不得保存 embedding 向量；向量只能写入 Lucene index。
- **SPEC-WIKI-SEM-DB-006**: `doc_id` 必须稳定且可由 `entry_id`, `doc_type`, `text_hash`, `embedding_model`, `embedding_dimensions` 推导或唯一映射。
- **SPEC-WIKI-SEM-DB-007**: 删除或重建 entry 时，关联语义文档必须标记为 `STALE` 或从 Lucene 删除。

---

## 8. WikiEntry 模型

```java
record WikiEntry(
    String id,
    WikiLevel level,
    Instant periodStart,
    Instant periodEnd,
    String timezone,
    WikiStatus status,
    String summary,
    String primaryTask,
    List<TaskSegment> taskSegments,
    WikiMetrics metrics,
    List<String> sourceEntryIds,
    String model,
    String promptVersion,
    int retryCount,
    Instant nextRetryAt,
    String lastError,
    Instant createdAt,
    Instant updatedAt,
    Instant summarizedAt
) {}
```

### 8.1 TaskSegment

```java
record TaskSegment(
    String title,
    String summary,
    List<String> evidence,
    List<String> apps,
    String confidence
) {}
```

- **SPEC-WIKI-MDL-001**: `TaskSegment.title` 是任务片段的短标题，不超过 80 个字符。
- **SPEC-WIKI-MDL-002**: `TaskSegment.summary` 是任务片段描述，不超过 500 个字符。
- **SPEC-WIKI-MDL-003**: `TaskSegment.evidence` 只能保存脱敏后的证据描述，不得保存完整 OCR/UIA 原文。
- **SPEC-WIKI-MDL-004**: `TaskSegment.confidence` 值必须为 `high`, `medium`, `low` 之一。

### 8.2 WikiMetrics

```java
record WikiMetrics(
    long activeSeconds,
    long afkSeconds,
    int switchCount,
    List<AppDuration> topApps,
    Map<String, Object> extra
) {}
```

- **SPEC-WIKI-MDL-005**: `activeSeconds` 必须为非负数。
- **SPEC-WIKI-MDL-006**: `afkSeconds` 必须为非负数。
- **SPEC-WIKI-MDL-007**: `topApps` 按耗时降序排列，默认最多 10 个。
- **SPEC-WIKI-MDL-008**: `extra` 可保存层级特有指标，如任务主题、重复模式、异常变化。

---

## 9. 原始事件输入规格

### 9.1 Bucket 发现

- **SPEC-WIKI-SRC-001**: 默认窗口 bucket 名称为 `aw-watcher-window_{hostname}`。
- **SPEC-WIKI-SRC-002**: 默认 AFK bucket 名称为 `aw-watcher-afk_{hostname}`。
- **SPEC-WIKI-SRC-003**: 默认内容 bucket 名称为 `aw-watcher-content_{hostname}`。
- **SPEC-WIKI-SRC-004**: 任意 bucket 缺失时，生成过程不得崩溃；缺失部分指标置空或为 0。

### 9.2 输入聚合

- **SPEC-WIKI-SRC-005**: 窗口事件用于计算应用耗时、窗口切换次数、窗口标题样本。
- **SPEC-WIKI-SRC-006**: AFK 事件用于计算非活跃时间。
- **SPEC-WIKI-SRC-007（已废弃）**: 旧版允许把内容事件正文用于 prompt；由
  `SPEC-CTP-040` 的标题事实契约取代。
- **SPEC-WIKI-SRC-008（已废弃）**: 旧版只禁止把 `text_content` 写入 Wiki；当前
  `SPEC-CTP-011` 已在 AW 入库前禁止该字段。
- **SPEC-WIKI-SRC-009（已废弃）**: 不再采样内容文本；标题预算由 `SPEC-CTP-040` 约束。

### 9.3 Prompt 输入限制

- **SPEC-WIKI-SRC-010**: 单次 prompt 输入总字符数默认不得超过 `wiki.prompt.maxContentChars`。
- **SPEC-WIKI-SRC-011**: 单个窗口标题样本默认不得超过 160 个字符。
- **SPEC-WIKI-SRC-012（已废弃）**: 不再存在内容文本片段输入。
- **SPEC-WIKI-SRC-013（已废弃）**: 改为按 `(app, effectiveTitle, contextKind)` 去重标题样本。

---

## 10. 摘要生成规格

### 10.1 直接从原始事件生成

- **SPEC-WIKI-GEN-001**: `HOUR` 摘要必须由该小时范围内的原始 AW 事件生成。
- **SPEC-WIKI-GEN-002**: `HALF_DAY` 摘要必须由该半天范围内的原始 AW 事件生成。
- **SPEC-WIKI-GEN-003**: `DAY` 摘要必须由该自然日范围内的原始 AW 事件生成。
- **SPEC-WIKI-GEN-004**: `HALF_DAY` 不得由 12 个小时摘要合并生成。
- **SPEC-WIKI-GEN-005**: `DAY` 不得由 2 个半天摘要或 24 个小时摘要合并生成。

### 10.2 从摘要生成

- **SPEC-WIKI-GEN-006**: `WEEK` 摘要只能由该周内 `SUMMARIZED` 的 `DAY` 摘要生成。
- **SPEC-WIKI-GEN-007**: `BIWEEK` 摘要只能由两个 `SUMMARIZED` 的 `WEEK` 摘要生成。
- **SPEC-WIKI-GEN-008**: `MONTH` 摘要只能由该自然月内 `SUMMARIZED` 的 `DAY` 摘要生成。
- **SPEC-WIKI-GEN-009**: 如果父级摘要依赖的子摘要未完成，父级必须保持 `PENDING`。

### 10.3 LLM 输出契约

LLM 必须返回 JSON，禁止 Markdown 代码块:

```json
{
  "summary": "该时间段的整体任务摘要",
  "primaryTask": "最主要任务",
  "taskSegments": [
    {
      "title": "任务标题",
      "summary": "任务片段摘要",
      "evidence": ["脱敏证据描述"],
      "apps": ["应用名"],
      "confidence": "high"
    }
  ],
  "metrics": {
    "activeSeconds": 0,
    "afkSeconds": 0,
    "switchCount": 0,
    "topApps": []
  }
}
```

- **SPEC-WIKI-GEN-010**: JSON 解析失败必须标记为 `FAILED`。
- **SPEC-WIKI-GEN-011**: 缺失 `summary` 或 `primaryTask` 时必须标记为 `FAILED`。
- **SPEC-WIKI-GEN-012**: `metrics` 中的本地计算字段以本地聚合结果为准，LLM 不得覆盖原始统计值。
- **SPEC-WIKI-GEN-013（已废弃）**: 第一版 `prompt_version` 曾固定为 `wiki-v1`；
  标题型 prompt 当前使用 `wiki-v3`，并由 `WikiSummarizer.promptVersion()` 作为唯一持久化来源，
  见 `SPEC-CTP-040`。

### 10.4 语义索引文本生成

- **SPEC-WIKI-SEM-IDX-001**: 每个 `SUMMARIZED` entry 必须生成一条 `ENTRY_SUMMARY` 语义文档。
- **SPEC-WIKI-SEM-IDX-002**: 每个 task segment 必须生成一条 `TASK_SEGMENT` 语义文档。
- **SPEC-WIKI-SEM-IDX-003**: `ENTRY_SUMMARY` 索引文本必须由 `summary`, `primaryTask`, `level`, `period` 和 top task titles 拼接生成。
- **SPEC-WIKI-SEM-IDX-004**: `TASK_SEGMENT` 索引文本必须由 segment `title`, `summary`, `apps`, `confidence` 拼接生成。
- **SPEC-WIKI-SEM-IDX-005**: 索引文本不得包含原始 OCR/UIA `text_content`。
- **SPEC-WIKI-SEM-IDX-006**: Lucene 文档字段必须包含 `doc_id`, `entry_id`, `doc_type`, `level`, `period_start`, `period_end`, `text`, `embedding`。
- **SPEC-WIKI-SEM-IDX-007**: Lucene `embedding` 字段必须使用 float vector，similarity 必须使用 cosine。
- **SPEC-WIKI-SEM-IDX-008**: Lucene 索引必须可从 `wiki_entries` 和 `wiki_semantic_documents` 全量重建。
- **SPEC-WIKI-SEM-IDX-009**: `matchedText` 可返回规范化索引文本的安全摘要，但不得返回完整原始 prompt 或 OCR/UIA 原文。

### 10.5 Embedding API

- **SPEC-WIKI-EMB-001**: embedding 必须使用 OpenAI-compatible `POST {embedding.base-url}/embeddings`。
- **SPEC-WIKI-EMB-002**: 请求体必须包含 `model`, `input`, `encoding_format="float"`；如配置 `embedding.dimensions`，则必须包含 `dimensions`。
- **SPEC-WIKI-EMB-003**: 返回向量长度必须等于 `embedding.dimensions`，否则语义文档必须标记为 `FAILED`。
- **SPEC-WIKI-EMB-004**: embedding 请求失败不得影响 Wiki 摘要状态。
- **SPEC-WIKI-EMB-005**: embedding worker 必须使用单线程低速处理，失败按递增退避重试。
- **SPEC-WIKI-EMB-006**: API key、请求体、完整索引文本不得写入日志或 `last_error`。
- **SPEC-WIKI-EMB-007**: `EmbeddingClient` 必须只暴露生成向量所需的最小接口，不得让调用方依赖 OpenAI HTTP 响应结构。

---

## 11. 后台 Worker 规格

### 11.1 启动与关闭

- **SPEC-WIKI-WKR-001**: `AppSession` 启动时，如果 `wiki.enabled=true`，必须创建 `WikiWorker`。
- **SPEC-WIKI-WKR-002**: `wiki.backfill.enabled=true` 时，`WikiWorker` 启动后必须先 enqueue 最近 7 天的历史缺失时间块。
- **SPEC-WIKI-WKR-003**: `WikiWorker` 必须使用单线程后台执行，不阻塞主服务启动。
- **SPEC-WIKI-WKR-004**: `AppSession.close()` 必须调用 `WikiWorker.shutdown()`。
- **SPEC-WIKI-WKR-005**: `shutdown()` 不得中断正在提交的数据库事务。

### 11.2 全量历史补算

- **SPEC-WIKI-WKR-006**: 第一版历史范围起点默认为启动时刻前 7 天，避免首次启用产生无界 LLM 调用。
- **SPEC-WIKI-WKR-007**: 历史范围终点必须为当前已结束的最大时间块。
- **SPEC-WIKI-WKR-008**: 缺失时间块必须以 `PENDING` 形式写入 `wiki_entries`。
- **SPEC-WIKI-WKR-009**: 已存在 `SUMMARIZED` 条目不得被补算任务覆盖。
- **SPEC-WIKI-WKR-010**: 全量补算必须低速执行，每轮默认最多处理一个 due entry。

### 11.3 调度顺序

推荐优先级:

1. `HOUR`
2. `HALF_DAY`
3. `DAY`
4. `WEEK`
5. `BIWEEK`
6. `MONTH`

- **SPEC-WIKI-WKR-011**: worker 每轮必须优先处理 `PENDING` 的低层级条目。
- **SPEC-WIKI-WKR-012**: 父级条目只有在依赖满足时才可处理。
- **SPEC-WIKI-WKR-013**: 到期的 `FAILED` 条目可重新进入处理队列。

### 11.4 重试

- **SPEC-WIKI-WKR-014**: 生成失败时必须递增 `retry_count`。
- **SPEC-WIKI-WKR-015**: 失败时必须保存 `last_error`。
- **SPEC-WIKI-WKR-016**: `next_retry_at` 使用递增退避，第一版可采用 `min(24h, 2^retry_count minutes)`。
- **SPEC-WIKI-WKR-017**: LLM 未配置或 Agent 不可用时，不得把条目标记为 `SKIPPED`，应保持可重试状态。

### 11.5 持续时间块发现

- **SPEC-WIKI-WKR-018**: 每轮处理 `PENDING` 前必须先对账上次成功游标至当前时刻之间所有已结束时间块。
- **SPEC-WIKI-WKR-019**: 时间块发现必须幂等，已存在的相同 `level + period + timezone` 不得重复创建或覆盖。
- **SPEC-WIKI-WKR-020**: `wiki.backfill.enabled=false` 时，首次轮次仍须对账最近一个小时，之后持续发现新结束时间块。
- **SPEC-WIKI-WKR-021**: 对账失败时不得推进游标，下一轮必须重试同一时间窗口。
- **SPEC-WIKI-WKR-022**: 父级时间块必须在每个预期子时间块都存在且为 `SUMMARIZED` 或 `SKIPPED` 后才能处理。
- **SPEC-WIKI-WKR-023**: 重试队列必须按层级扫描所有到期条目，不完整父级不得阻塞其子级或其他可处理条目。

### 11.6 语义索引 Worker

- **SPEC-WIKI-SEM-WKR-001**: `AppSession` 启动时，如果 `wiki.semantic.enabled=true` 且 `embedding.enabled=true`，必须创建 `WikiEmbeddingWorker`。
- **SPEC-WIKI-SEM-WKR-002**: `WikiEmbeddingWorker` 必须使用单线程后台执行，不阻塞主服务启动或 Wiki 摘要 worker。
- **SPEC-WIKI-SEM-WKR-003**: `WikiEmbeddingWorker` 每轮默认最多处理一个 due semantic document。
- **SPEC-WIKI-SEM-WKR-004**: `WikiEmbeddingWorker` 必须优先处理 `PENDING` 文档，再处理到期的 `FAILED` 文档。
- **SPEC-WIKI-SEM-WKR-005**: embedding 成功后必须先写入 Lucene，再把 `wiki_semantic_documents.status` 更新为 `INDEXED`。
- **SPEC-WIKI-SEM-WKR-006**: Lucene 写入失败时不得把语义文档标记为 `INDEXED`。
- **SPEC-WIKI-SEM-WKR-007**: `AppSession.close()` 必须调用 `WikiEmbeddingWorker.shutdown()`。
- **SPEC-WIKI-SEM-WKR-008**: `shutdown()` 不得中断正在提交的 SQLite 事务或 Lucene commit。

---

## 12. Agent 工具规格

### 12.1 工具方法

`WikiTools` 必须提供:

| 方法 | 描述 |
|------|------|
| `queryWiki(start, end, level)` | 按时间段查询摘要，`level` 可为空 |
| `wikiStatus(start, end)` | 查询时间段内已完成、待处理、失败统计 |
| `semanticSearchWiki(query, start, end, level, topK)` | 按自然语言主题检索 Wiki 摘要和任务片段 |

- **SPEC-WIKI-TOL-001**: 所有公开工具方法必须标注 `@Tool`。
- **SPEC-WIKI-TOL-002**: 所有参数必须标注 `@ToolParam`。
- **SPEC-WIKI-TOL-003**: `start` 和 `end` 接收 ISO-8601 字符串。
- **SPEC-WIKI-TOL-004**: `level` 为空时必须自动选择粒度。

### 12.2 自动粒度选择

- **SPEC-WIKI-TOL-005**: 查询跨度小于等于 6 小时时，优先返回 `HOUR`。
- **SPEC-WIKI-TOL-006**: 查询跨度大于 6 小时且小于等于 2 天时，优先返回 `HALF_DAY` 和 `DAY`。
- **SPEC-WIKI-TOL-007**: 查询跨度大于 2 天且小于等于 14 天时，优先返回 `DAY`。
- **SPEC-WIKI-TOL-008**: 查询跨度大于 14 天且小于等于 60 天时，优先返回 `WEEK`。
- **SPEC-WIKI-TOL-009**: 查询跨度大于 60 天时，优先返回 `MONTH`。
- **SPEC-WIKI-TOL-010**: 如果首选粒度缺失，工具必须返回缺失提示，并可降级返回可用粒度。

### 12.3 输出契约

`queryWiki` 返回 JSON 字符串:

```json
{
  "entries": [
    {
      "level": "DAY",
      "periodStart": "2026-06-08T00:00:00Z",
      "periodEnd": "2026-06-09T00:00:00Z",
      "summary": "...",
      "primaryTask": "...",
      "taskSegments": [],
      "metrics": {},
      "status": "SUMMARIZED"
    }
  ],
  "missing": [],
  "pending": [],
  "failed": []
}
```

- **SPEC-WIKI-TOL-011**: 工具输出不得包含完整 OCR/UIA 原文。
- **SPEC-WIKI-TOL-012**: 工具输出必须包含缺失、待处理、失败时间块信息，帮助 Agent 解释不完整结果。

### 12.4 语义检索工具

`semanticSearchWiki` 返回 JSON 字符串:

```json
{
  "results": [
    {
      "score": 0.82,
      "docType": "TASK_SEGMENT",
      "entryId": "day-2026-06-08",
      "level": "DAY",
      "periodStart": "2026-06-08T00:00:00Z",
      "periodEnd": "2026-06-09T00:00:00Z",
      "summary": "...",
      "primaryTask": "...",
      "matchedText": "..."
    }
  ],
  "error": null,
  "fallbackSuggestion": null
}
```

- **SPEC-WIKI-SEM-TOL-001**: `query` 必填，必须使用 embedding model 生成查询向量。
- **SPEC-WIKI-SEM-TOL-002**: `start`, `end`, `level` 可选，用作过滤条件。
- **SPEC-WIKI-SEM-TOL-003**: `topK` 默认来自 `wiki.semantic.topK`，上限为 `50`。
- **SPEC-WIKI-SEM-TOL-004**: 返回结果必须包含 `score`, `docType`, `entryId`, `level`, `periodStart`, `periodEnd`, `summary`, `primaryTask`, `matchedText`。
- **SPEC-WIKI-SEM-TOL-005**: 如果语义索引不可用，工具必须返回结构化错误，并给出建议使用 `queryWiki` 的 `fallbackSuggestion`。
- **SPEC-WIKI-SEM-TOL-006**: Agent 对模糊主题查询应优先调用 `semanticSearchWiki`，例如“最近什么时候在处理配置问题”。
- **SPEC-WIKI-SEM-TOL-007**: `semanticSearchWiki` 不得返回完整 OCR/UIA 原文、embedding 向量或 API key 相关信息。

---

## 13. Agent Prompt 集成

- **SPEC-WIKI-AGT-001**: `SelfAnalystAgent` system prompt 必须说明 LLM Wiki 的用途。
- **SPEC-WIKI-AGT-002**: 当用户询问过去某时间段做了什么、任务分布、趋势变化、复盘对比时，Agent 应优先调用 `WikiTools`。
- **SPEC-WIKI-AGT-003**: 当 Wiki 返回 pending 或 failed 区间时，Agent 必须明确说明摘要仍在生成或生成失败。
- **SPEC-WIKI-AGT-004**: Agent 不得声称没有数据，除非 `WikiTools` 和 ActivityWatch 原始查询都没有可用结果。
- **SPEC-WIKI-AGT-005**: 当用户问题只有主题、现象或任务描述而没有明确时间范围时，Agent 应优先尝试 `semanticSearchWiki`。

---

## 14. 配置规格

新增配置:

| 属性 | 环境变量 | 默认值 | 类型 | 说明 |
|------|----------|--------|------|------|
| `wiki.enabled` | `WIKI_ENABLED` | `false` | boolean | 是否启用 Wiki |
| `wiki.backfill.enabled` | `WIKI_BACKFILL_ENABLED` | `false` | boolean | 是否启动最近 7 天历史补算 |
| `wiki.worker.intervalSeconds` | `WIKI_WORKER_INTERVAL_SECONDS` | `60` | int | worker 轮询间隔 |
| `wiki.prompt.maxContentChars` | `WIKI_PROMPT_MAX_CONTENT_CHARS` | `12000` | int | prompt 内容上限 |
| `wiki.topApps.limit` | `WIKI_TOP_APPS_LIMIT` | `10` | int | top app 最大数量 |
| `wiki.semantic.enabled` | `WIKI_SEMANTIC_ENABLED` | `true` | boolean | 是否启用 Wiki 语义索引 |
| `wiki.semantic.index-dir` | `WIKI_SEMANTIC_INDEX_DIR` | `{memory.dir}/wiki-semantic-index` | Path | Lucene 向量索引目录 |
| `wiki.semantic.topK` | `WIKI_SEMANTIC_TOP_K` | `8` | int | 默认语义检索返回数量 |
| `embedding.enabled` | `EMBEDDING_ENABLED` | `false` | boolean | 是否启用 embedding 请求 |
| `embedding.base-url` | `EMBEDDING_BASE_URL` | `https://api.openai.com/v1` | String (URL) | OpenAI-compatible embedding base URL |
| `embedding.api-key` | `EMBEDDING_API_KEY` | fallback to `OPENAI_API_KEY` | String | embedding API key |
| `embedding.model` | `EMBEDDING_MODEL` | `text-embedding-3-small` | String | embedding model |
| `embedding.dimensions` | `EMBEDDING_DIMENSIONS` | `1024` | int | embedding 向量维度 |

- **SPEC-WIKI-CFG-001**: 配置加载优先级必须遵循现有 `Config` 规则。
- **SPEC-WIKI-CFG-002**: `wiki.enabled=false` 时不得启动 worker，也不得注册写入任务；已存在的 `WikiTools` 可只读查询。
- **SPEC-WIKI-CFG-003**: `wiki.backfill.enabled=false` 时不得 enqueue 历史缺失时间块，但可处理未来新增时间块。
- **SPEC-WIKI-CFG-004**: `wiki.prompt.maxContentChars` 小于 1000 时必须回退到默认值。
- **SPEC-WIKI-CFG-005**: `wiki.semantic.enabled=false` 时不得启动 `WikiEmbeddingWorker`，`semanticSearchWiki` 必须返回结构化不可用错误。
- **SPEC-WIKI-CFG-006**: `wiki.semantic.topK` 小于 1 或大于 50 时必须回退到默认值 `8`。
- **SPEC-WIKI-CFG-007**: `embedding.enabled=false` 时不得发起 embedding HTTP 请求。
- **SPEC-WIKI-CFG-008**: `embedding.api-key` 未配置时，必须尝试读取 `OPENAI_API_KEY`；仍未配置时语义索引保持不可用但 Wiki 摘要功能继续可用。
- **SPEC-WIKI-CFG-009**: `embedding.dimensions` 必须为正整数，非法值必须回退到默认值 `1024`。

---

## 15. 隐私和安全

- **SPEC-WIKI-PRV-001**: `llm-wiki.db` 不得保存完整 `text_content`；Wiki 事实构建器也不得读取该字段。
- **SPEC-WIKI-PRV-002**: 数据库允许保存应用名、聚合耗时、切换次数、摘要、任务片段、脱敏证据描述。
- **SPEC-WIKI-PRV-003**: API Key、LLM base URL、用户配置敏感字段不得写入 Wiki 数据库。
- **SPEC-WIKI-PRV-004**: prompt 输入必须有硬性字符上限。
- **SPEC-WIKI-PRV-005**: 生成摘要失败时，`last_error` 不得包含 prompt 原文。
- **SPEC-WIKI-PRV-006**: 日志不得输出完整 prompt 或完整 OCR/UIA 文本。
- **SPEC-WIKI-PRV-007**: Lucene 文档不得包含完整 OCR/UIA `text_content`。
- **SPEC-WIKI-PRV-008**: `wiki_semantic_documents.last_error` 不得包含 API key、embedding 请求体或完整索引文本。
- **SPEC-WIKI-PRV-009**: 语义检索返回的 `matchedText` 必须来自规范化摘要文本，不得来自原始屏幕文本。
- **SPEC-WIKI-PRV-011**: Wiki prompt 只能接收窗口标题、上下文标题、聚合指标和既有派生摘要，
  不得接收“屏幕内容片段”。
- **SPEC-WIKI-PRV-010**: embedding 向量不得写入 `llm-wiki.db` 或日志。

---

## 16. 错误处理

- **SPEC-WIKI-ERR-001**: 数据库初始化失败时，应用主服务不得崩溃；必须记录 warning 并禁用 Wiki worker。
- **SPEC-WIKI-ERR-002**: 单个时间块生成失败不得终止 worker。
- **SPEC-WIKI-ERR-003**: LLM 超时必须标记为 `FAILED` 并设置重试时间。
- **SPEC-WIKI-ERR-004**: 原始 bucket 查询失败时，如果其他 bucket 有数据，允许继续生成；如果全部失败，标记为 `FAILED`。
- **SPEC-WIKI-ERR-005**: 无任何事件的时间块标记为 `SKIPPED`。
- **SPEC-WIKI-ERR-006**: Lucene 索引目录初始化失败时，应用主服务和普通 Wiki 查询不得崩溃；必须禁用语义检索并返回结构化错误。
- **SPEC-WIKI-ERR-007**: embedding 服务不可用时，语义文档必须标记为 `FAILED` 并设置重试时间，普通 Wiki 摘要状态不得改变。
- **SPEC-WIKI-ERR-008**: 查询向量生成失败时，`semanticSearchWiki` 必须返回结构化错误和 `queryWiki` 降级建议。
- **SPEC-WIKI-ERR-009**: Lucene 查询失败时不得回退到无过滤的全量摘要输出。

---

## 17. 实现范围

### 17.1 必改文件

- `pom.xml`
- `self-analyst-wiki/pom.xml`
- `self-analyst-wiki/src/main/java/com/selfanalyst/wiki/*`
- `self-analyst-wiki/src/test/java/com/selfanalyst/wiki/*`
- `self-analyst-app/src/main/java/com/selfanalyst/config/Config.java`
- `self-analyst-app/src/main/java/com/selfanalyst/AppSession.java`
- `self-analyst-app/src/main/java/com/selfanalyst/agent/SelfAnalystAgent.java`
- `docs/specs/llm-wiki.md`
- `docs/README.md`

### 17.2 不应修改的文件

第一版不需要修改:

- `self-analyst-aw` 的 SQLite schema。
- `self-analyst-content` 的采集逻辑。
- `self-analyst-desktop` Tauri 壳。
- `self-analyst-app/src/main/resources/desktop-ui/*`。

### 17.3 Maven 依赖

`self-analyst-wiki/pom.xml` 必须新增 Lucene `10.4.0` 依赖:

```xml
<dependency>
    <groupId>org.apache.lucene</groupId>
    <artifactId>lucene-core</artifactId>
</dependency>
<dependency>
    <groupId>org.apache.lucene</groupId>
    <artifactId>lucene-analysis-common</artifactId>
</dependency>
<dependency>
    <groupId>org.apache.lucene</groupId>
    <artifactId>lucene-queryparser</artifactId>
</dependency>
```

- **SPEC-WIKI-DEP-001**: Lucene 版本必须在父级 dependency management 或 `self-analyst-wiki/pom.xml` 中固定为 `10.4.0`。
- **SPEC-WIKI-DEP-002**: 语义索引不得引入外部向量数据库服务依赖。

---

## 18. 测试规格

### 18.1 Store 测试

- **SPEC-WIKI-TST-001**: `WikiStoreTest.shouldCreateSchema` 验证数据库和表创建。
- **SPEC-WIKI-TST-002**: `WikiStoreTest.shouldUpsertUniquePeriod` 验证同一层级同一时间块唯一。
- **SPEC-WIKI-TST-003**: `WikiStoreTest.shouldTransitionStatus` 验证 `PENDING → SUMMARIZED`, `PENDING → FAILED`, `PENDING → SKIPPED`。
- **SPEC-WIKI-TST-004**: `WikiStoreTest.shouldRoundTripJsonFields` 验证 JSON 字段序列化和反序列化。
- **SPEC-WIKI-TST-005**: `WikiStoreTest.shouldQueryByPeriodAndLevel` 验证按时间段和层级查询。

### 18.2 Worker 测试

- **SPEC-WIKI-TST-006**: `WikiWorkerTest.shouldNotProcessOpenPeriods` 验证当前未结束时间块不处理。
- **SPEC-WIKI-TST-007**: `WikiWorkerTest.shouldEnqueueHistoricalPeriods` 验证历史全量补算入队。
- **SPEC-WIKI-TST-008**: `WikiWorkerTest.parentWaitsUntilEveryExpectedChildPeriodExists` 验证父级等待全部预期子级完成。
- **SPEC-WIKI-TST-009**: `WikiWorkerTest.shouldRetryFailedEntries` 验证失败退避和重试。
- **SPEC-WIKI-TST-010**: `WikiWorkerTest.shouldContinueAfterSingleEntryFailure` 验证单条失败不终止 worker。
- **SPEC-WIKI-TST-020**: `WikiWorkerTest.runningWorkerEnqueuesRecentlyCompletedPeriodsWithoutBackfill` 验证关闭历史补算后仍持续发现时间块。
- **SPEC-WIKI-TST-021**: `WikiWorkerTest.delayedRoundCatchesUpEveryCompletedHourExactlyOnce` 验证延迟轮次完整补齐且幂等。
- **SPEC-WIKI-TST-022**: `WikiWorkerTest.failedStartupBackfillIsRetriedFromTheOriginalCursor` 验证启动补算失败不会丢失游标。
- **SPEC-WIKI-TST-023**: `WikiWorkerTest.parentSummaryUsesOnlyChildrenFromItsOwnTimezone` 验证父级只聚合边界与时区精确匹配的子级。
- **SPEC-WIKI-TST-024**: `WikiPeriodFactoryTest.shouldNotIncludeBlockEndingAfterRangeEnd` 验证 range end 之后才结束的时间块不会提前入队。
- **SPEC-WIKI-TST-025**: `WikiWorkerTest.incompleteFailedParentDoesNotBlockRetryableChild` 验证不完整失败父级不会造成重试队列活锁。

### 18.3 Summarizer 测试

- **SPEC-WIKI-TST-011**: `WikiSummarizerTest.shouldUseRawFactsForHourHalfDayAndDay` 验证小时、半天、天使用原始 facts。
- **SPEC-WIKI-TST-012**: `WikiSummarizerTest.shouldUseChildSummariesForWeekBiweekMonth` 验证周、双周、月使用摘要输入。
- **SPEC-WIKI-TST-013**: `WikiSummarizerTest.shouldRejectInvalidJson` 验证非法 JSON 失败。
- **SPEC-WIKI-TST-014**: `WikiSummarizerTest.shouldPreserveLocalMetrics` 验证本地指标不被 LLM 覆盖。

### 18.4 Tools 测试

- **SPEC-WIKI-TST-015**: `WikiToolsTest.shouldAutoSelectLevelByRange` 验证自动粒度选择。
- **SPEC-WIKI-TST-016**: `WikiToolsTest.shouldRespectExplicitLevel` 验证指定粒度查询。
- **SPEC-WIKI-TST-017**: `WikiToolsTest.shouldReportPendingAndFailedRanges` 验证缺失、待处理、失败提示。

### 18.5 回归测试

- **SPEC-WIKI-TST-018**: `mvn test` 必须通过现有所有测试。
- **SPEC-WIKI-TST-019**: LLM 未配置时应用可启动，Wiki worker 不写入伪摘要。

### 18.6 语义索引测试

- **SPEC-WIKI-SEM-TST-001**: `OpenAiCompatibleEmbeddingClientTest` 验证请求体、响应解析、维度校验、错误处理。
- **SPEC-WIKI-SEM-TST-002**: `WikiSemanticIndexTest` 验证 Lucene 写入、KNN 查询、过滤、删除和重建。
- **SPEC-WIKI-SEM-TST-003**: `WikiEmbeddingWorkerTest` 验证摘要完成后入队、失败重试、stale 重建。
- **SPEC-WIKI-SEM-TST-004**: `WikiToolsTest` 覆盖 `semanticSearchWiki` 的命中、过滤、topK 和错误降级。
- **SPEC-WIKI-SEM-TST-005**: 隐私测试必须确认 Lucene 文档和 SQLite 元数据不包含原始 `text_content`。
- **SPEC-WIKI-SEM-TST-006**: 回归运行 `mvn test`，现有 Wiki/Agent/Memory 测试不受影响。

---

## 19. 验收标准

- **SPEC-WIKI-ACC-001**: 应用启动后主服务不被 Wiki 历史补算阻塞。
- **SPEC-WIKI-ACC-002**: 已结束小时能生成 `HOUR` 摘要。
- **SPEC-WIKI-ACC-003**: 已结束半天能直接从原始事件生成 `HALF_DAY` 摘要。
- **SPEC-WIKI-ACC-004**: 已结束自然日能直接从原始事件生成 `DAY` 摘要。
- **SPEC-WIKI-ACC-005**: 周、双周、月能从下级摘要生成趋势摘要。
- **SPEC-WIKI-ACC-006**: Agent 能通过 Wiki 回答指定时间段任务复盘问题。
- **SPEC-WIKI-ACC-007**: LLM 不可用时条目保持可重试状态，不写入低质量摘要。
- **SPEC-WIKI-ACC-008**: `llm-wiki.db` 不包含完整 OCR/UIA 原文。
- **SPEC-WIKI-ACC-009**: `mvn test` 通过。
- **SPEC-WIKI-ACC-010**: 生成 Wiki 摘要后，entry summary 和 task segments 能异步进入语义索引。
- **SPEC-WIKI-ACC-011**: 用户可通过自然语言主题搜索跨时间段命中相关 Wiki 条目。
- **SPEC-WIKI-ACC-012**: embedding 服务不可用时，普通 Wiki 摘要生成和时间段查询仍可用。
- **SPEC-WIKI-ACC-013**: embedding model 或 dimensions 配置变化后，旧索引能标记 `STALE` 并重建。
- **SPEC-WIKI-ACC-014**: 数据库和 Lucene 索引均不保存原始屏幕文本。

---

## 20. 参考资料

- [Apache Lucene Downloads](https://lucene.apache.org/core/downloads.html): Lucene `10.4.0` release。
- [OpenAI Vector embeddings guide](https://developers.openai.com/api/docs/guides/embeddings): embeddings API、`text-embedding-3-small`、`encoding_format="float"` 和默认维度说明。

---

## 21. 规格追溯矩阵

| 规格 ID | 目标文件/组件 | 验证方式 |
|---------|---------------|----------|
| SPEC-WIKI-GOAL-* | `docs/specs/llm-wiki.md` | 规格审查 |
| SPEC-WIKI-ARCH-* | `com.selfanalyst.wiki.*`, `AppSession`, `SelfAnalystAgent` | 代码审查 |
| SPEC-WIKI-FLOW-* | `WikiWorker`, `WikiSummarizer` | Worker/Summarizer 测试 |
| SPEC-WIKI-TIME-* | `WikiPeriodFactory` | 单元测试 |
| SPEC-WIKI-DB-* | `WikiStore` | Store 测试 |
| SPEC-WIKI-STAT-* | `WikiStatus`, `WikiStore` | Store/Worker 测试 |
| SPEC-WIKI-MDL-* | `WikiEntry`, JSON mapper | Store 测试 |
| SPEC-WIKI-SRC-* | `WikiFactBuilder` | FactBuilder/Summarizer 测试 |
| SPEC-WIKI-GEN-* | `WikiSummarizer` | Summarizer 测试 |
| SPEC-WIKI-WKR-* | `WikiWorker`, `AppSession` | Worker 测试 |
| SPEC-WIKI-TOL-* | `WikiTools`, `SelfAnalystAgent` | Tools 测试 |
| SPEC-WIKI-AGT-* | `SelfAnalystAgent` | Prompt/工具注册测试 |
| SPEC-WIKI-CFG-* | `Config`, `application.properties` | Config 测试 |
| SPEC-WIKI-PRV-* | `WikiFactBuilder`, `WikiStore`, logging | 隐私检查 |
| SPEC-WIKI-ERR-* | `WikiWorker`, `WikiStore` | 错误场景测试 |
| SPEC-WIKI-DEP-* | `pom.xml`, `self-analyst-wiki/pom.xml` | Maven 依赖检查 |
| SPEC-WIKI-SEM-ARCH-* | `EmbeddingClient`, `WikiSemanticIndex`, `WikiEmbeddingWorker`, `WikiTools` | 代码审查 |
| SPEC-WIKI-SEM-FLOW-* | `WikiWorker`, `WikiEmbeddingWorker`, `WikiSemanticIndex` | Worker/Index 测试 |
| SPEC-WIKI-SEM-DB-* | `WikiStore`, `wiki_semantic_documents` | Store 测试 |
| SPEC-WIKI-SEM-IDX-* | `WikiSemanticIndex`, `WikiEmbeddingWorker` | Index 测试 |
| SPEC-WIKI-EMB-* | `OpenAiCompatibleEmbeddingClient`, `WikiEmbeddingWorker` | Embedding client 测试 |
| SPEC-WIKI-SEM-WKR-* | `WikiEmbeddingWorker`, `AppSession` | Worker 测试 |
| SPEC-WIKI-SEM-TOL-* | `WikiTools.semanticSearchWiki` | Tools 测试 |
| SPEC-WIKI-SEM-TST-* | `src/test/java/com/selfanalyst/wiki/*` | `mvn test` |
| SPEC-WIKI-TST-* | `src/test/java/com/selfanalyst/wiki/*` | `mvn test` |
| SPEC-WIKI-ACC-* | 全功能链路 | 验收测试 |
