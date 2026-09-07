## Context

见 `proposal.md`。第一批已改存储标识。本 change 只改代码坐标与 Agent 可见工具名称/描述。HTTP `/api/0`、配置键 `aw.*`、规格 ID `SPEC-AW-*` 和规格目录 `activitywatch-tools` 保持稳定。

## Goals / Non-Goals

**Goals:**

- 模块、包、`EventServer`、`EventQueryTools` 与文档一致。
- 一次合入，仓库内不再引用旧模块名、旧包名或 `AwServer` / `ActivityWatchTools` 类型（归档文档除外）。

**Non-Goals:**

- 不改路由、配置键、桶 ID、表名、采集类名。
- 不保留旧 Maven artifact 的 relocation/别名。
- 不重命名 OpenSpec 能力路径 `activitywatch-tools`。

## Decisions

### 1. 模块名用 `self-analyst-events`，包名用 `com.selfanalyst.events`

与投影库 `events.db`、模块职责（嵌入式事件服务）对齐。不用 `self-analyst-store`，以免与 chat/wiki/file 其它存储混淆。

备选 `self-analyst-activity` 更含糊。包名不跟模块名逐段相同（没有 `com.selfanalyst.selfanalyst.events`）。

### 2. 用 `git mv` 一次搬目录，再批量改 import

先移动 `self-analyst-aw` → `self-analyst-events`，再移动 `.../com/selfanalyst/aw` → `.../com/selfanalyst/events`，然后替换 package/import/artifactId。避免 copy-delete 丢失 git 历史。

### 3. 工具方法名不变，只改类型名与描述

`listBuckets`、`queryEvents`、`executeAQL`、`getServerInfo`、`getSettings` 是 Agent 调用契约（SPEC-AW-002）。改方法名会强迫提示词和测试一起变，收益小。类型改名为 `EventQueryTools` 即可。

### 4. 配置键与 Java 属性

`aw.port` 等键不动。`Config.awPort()` 这类与键同名的访问器可保留，以免配置层再开一轮 breaking。字段 `awServer` 改为 `eventServer`，因为它持有的是类型而非配置键。

## Risks / Trade-offs

- [漏改一处 import 导致编译失败] → 全仓库搜索 `self-analyst-aw`、`com.selfanalyst.aw`、`AwServer`、`ActivityWatchTools`（排除 `docs/archive` 与 `openspec/changes/archive`）后跑 `mvn test`。
- [CI 仍 `-pl self-analyst-aw`] → 任务包含工作流与 `docs/testing.md`。
- [Agent 工具描述改了但规格未改] → 同步 `activitywatch-tools` / `agent-runtime` / `agent-context-compaction`。

## Migration Plan

无发布用户，无 Maven relocation。开发者重新 clone 或在本仓库编译即可；本地 `aw.*` 配置文件不必改。
