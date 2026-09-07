## Why

前两批已去掉采集桶、投影文件和 Maven/Java 坐标上的品牌标识，但运行时仍会把完整单词 `ActivityWatch` 写进 Agent 系统提示、config.toml 模板说明、日志和异常。这些会进入模型上下文、用户编辑的配置文件和日志，与「代码和生成数据不再保留该措辞、文档可保留」的目标不一致。

## What Changes

- Agent 系统提示与联网搜索提示改为「事件数据 / 事件查询工具 / 本地工具」，不再把本产品数据源或工具称为 ActivityWatch。
- `SupportedKeys` 中 `aw.mode` / `aw.port` / `aw.base-url` / `aw.timeout` / `aw.data-dir` 的中英说明改为事件服务用语；配置键名保持 `aw.*`。
- 本产品 Java 源码中的日志、异常文案与注释去掉 `ActivityWatch` 措辞。
- 不改 HTTP `/api/0`、配置键、导入识别的 `aw-watcher-*`、内嵌兼容 Web UI、`docs/` 与 OpenSpec 主规格里作为协议说明的 ActivityWatch 用语（本 change 只改上述运行时字符串相关条款）。

## Capabilities

### New Capabilities

- （无）

### Modified Capabilities

- `agent-runtime`: 基础 system prompt 描述本地事件数据与查询工具时 MUST NOT 使用 ActivityWatch 品牌名。
- `web-search`: 个人活动优先本地工具的提示与规格叙述改为事件查询/Wiki 等本地工具，不再点名 ActivityWatch。
- `user-configuration`: 空文件模板里 `aw.*` 键的双语说明 MUST NOT 使用 ActivityWatch 品牌名。

## Impact

- `prompts/agent/system.{zh,en}.md`、`web-search.{zh,en}.md` 及 `AgentPromptsTest`。
- `SupportedKeys` 描述与配置模板相关测试。
- `EventServer`、`LegacyDatabaseMigrator`、`ProjectionRebuildService` 的日志/异常；`EventProjector`、`ProjectionStatus` 注释；`application.properties` 分段注释。
- 不改 `self-analyst-events/.../webui/`、不改 `docs/`、不改配置键与导入前缀常量。
