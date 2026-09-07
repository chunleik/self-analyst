## Why

第一批已去掉采集桶和投影文件上的 `aw-` 品牌，但 Maven 模块、Java 包、嵌入式服务类和 Agent 工具类仍叫 `self-analyst-aw` / `com.selfanalyst.aw` / `AwServer` / `ActivityWatchTools`。当前版本从未发布，可以一次改完代码坐标，不必保留旧 artifact 别名。

## What Changes

- **BREAKING** Maven 模块 `self-analyst-aw` 重命名为 `self-analyst-events`（目录与 artifactId）。
- **BREAKING** Java 包 `com.selfanalyst.aw.**` 重命名为 `com.selfanalyst.events.**`。
- **BREAKING** `AwServer` 重命名为 `EventServer`，相关测试类同步改名。
- **BREAKING** `ActivityWatchTools` 重命名为 `EventQueryTools`；向 Agent 暴露的五个工具方法名不变（`listBuckets` 等），HTTP 仍请求已包含 `/api/0` 的 base URL。
- Agent 工具描述与截断提示改为中性“事件桶 / 投影查询”用语，不再把产品工具称为 ActivityWatch 客户端。
- 不改 HTTP `/api/0` 与 `/0` 路由、配置键 `aw.*`、`SPEC-AW-*` 追溯号、规格目录名 `activitywatch-tools`、SQLite 表名、采集桶 ID，也不改 `WindowWatcher` 等采集类名。

## Capabilities

### New Capabilities

- （无）

### Modified Capabilities

- `activitywatch-tools`: 工具由 `EventQueryTools` 注册；模型仍只看到五个公开方法；规格路径与 `SPEC-AW-*` ID 不变。
- `agent-runtime`: Agent 通过 `EventQueryTools` 查询投影，不直连数据库；采集链仍可调用本地兼容 HTTP API。
- `agent-context-compaction`: `queryEvents` 的 500 条与 80000 字符预算改述为事件查询工具，行为不变。

## Impact

- 模块：`self-analyst-aw/` → `self-analyst-events/`，根 pom、`self-analyst-app`、`self-analyst-wiki` 依赖。
- 源码与测试中全部 `com.selfanalyst.aw` 导入；`AppSession` 等持有 `AwServer` 的字段。
- `ActivityWatchToolsTest` 及 Agent 注册代码。
- 文档：`README.md`、`CONTRIBUTING.md`、`AGENTS.md`、`docs/architecture.md`、`docs/README.md`、`docs/testing.md`。
- CI 工作流若引用模块名则同步。
- `docs/archive/` 与已归档 change 不改。
- 配置键 `aw.port` / `aw.data-dir` / `aw.timeout` 保持，Java 访问这些键的属性名可继续与键对应。
