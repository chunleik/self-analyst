## Context

见 `proposal.md` 的 Why / What。当前采集器、内容策略、Wiki 事实查询和投影文件名硬编码 `aw-watcher-*` 与 `aw.db`。HTTP `/api/0` 与配置键 `aw.*` 保持不变。产品从未发布，不做本地改写迁移。

## Goals / Non-Goals

**Goals:**

- 本产品新写入使用 `watcher-*` 采集桶和 `wiki-*` 摘要桶。
- 投影文件与默认数据目录改为 `events.db` / `events/`。
- 来源识别同时覆盖本产品新名与兼容导入的 `aw-watcher-*`。
- 测试与现行规格、用户向文档与代码一致。

**Non-Goals:**

- 不改 HTTP 路径、SQLite 表名、`aw.*` 配置键、Maven 模块或 Java 包名。
- 不扫描或改写用户目录里已有的 `aw.db` / 旧桶 ID。
- 不把 Wiki 摘要桶改成 `watcher-wiki-*`。

## Decisions

### 1. 采集用 `watcher-{kind}_{hostname}`，去掉 `aw-`

本产品采集器（window / afk / content / file）的 client 同步为 `watcher-{kind}`。下划线仍分隔 hostname，与现有 `SAFE_BUCKET_ID` 和 URL 路径兼容。

备选：`sa-window_{host}` 增加品牌前缀但丢失 “这是采集器” 的语义；`sa-watcher-window_*` 改动最小但仍像克隆。

### 2. Wiki 时间线桶用 `wiki-{level}_{hostname}`

这些桶是 `llm-wiki.db` 的只读投影，不是采集。client 为 `wiki`。Wiki 事实构建只读 `watcher-window/afk/content`（及导入的 `aw-watcher-*` 同类桶），不读 `wiki-*`。

### 3. 导入仍识别 `aw-watcher-*`，本地不迁移

兼容 HTTP 与 DataImporter 仍可能收到官方 ActivityWatch 桶名。来源分类、内容策略、隐藏列表、Wiki 事实查询对**新旧前缀都匹配**。本机采集器只创建新名。目录里若留下开发期 `aw.db`，运行时忽略、不合并。

### 4. 配置键不动，只改默认路径与投影文件名

`SPEC-TOML-NON-001` 禁止重命名既有点分键。`aw.data-dir` 默认值改为 `.../events`（测试与文档中的 `aw-data` 同步替换）；`aw.raw.dir` 仍默认 `{aw.data-dir}/raw`。`aw.port` / `aw.mode` / `aw.base-url` 表示兼容服务，不改。

### 5. 模块与包名留待后续 change

`self-analyst-aw` 与 `com.selfanalyst.aw` 牵动 Maven、导入和大量测试。本次只改可观察存储标识，避免与标识重命名缠在一起。

## Risks / Trade-offs

- [开发机残留 `aw.db` 与新 `events.db` 并存] → 规格明确不合并；文档说明当前投影文件名。
- [只改写入、漏改 Wiki/摘要硬编码桶名导致时间线或 Wiki 空窗] → 任务按写入、识别、消费三条链路分别改并跑对应测试。
- [隐藏列表或内容策略漏掉新前缀] → 策略与默认列表同时匹配 `watcher-content_` 和 `aw-watcher-content_`。

## Migration Plan

无发布用户，无数据迁移、无回滚脚本。实现作为单一 breaking 标识切换合入；开发者删除本地 `aw-data` / `aw.db` 后按新路径重新采集即可。
