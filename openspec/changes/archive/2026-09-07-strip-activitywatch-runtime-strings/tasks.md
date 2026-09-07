## 1. Agent 提示与配置说明

- [x] 1.1 按 design 替换表更新 `prompts/agent/system.{zh,en}.md` 与 `web-search.{zh,en}.md`，同步 `AgentPromptsTest`；跑 `mvn -pl self-analyst-app '-Dtest=AgentPromptsTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`
- [x] 1.2 将 `SupportedKeys` 中 `aw.mode` / `aw.port` / `aw.base-url` / `aw.timeout` / `aw.data-dir` 的中英说明改为事件服务用语；跑配置模板相关测试（含 `DesktopConfigControllerTest` 若覆盖模板文本）确认键名未改且说明不含 ActivityWatch

## 2. 日志、异常与注释

- [x] 2.1 替换 `EventServer`、`LegacyDatabaseMigrator`、`ProjectionRebuildService` 中含 ActivityWatch 的日志与异常文案，以及 `EventProjector`、`ProjectionStatus`、`application.properties` 中的注释
- [x] 2.2 在 `src/main` 排除 `resources/webui/` 后搜索 `ActivityWatch` / `activitywatch`，确认本产品源码无残留

## 3. 规格同步与回归

- [x] 3.1 将本 change 的 delta 同步到 `openspec/specs/` 下 `agent-runtime`、`web-search`、`user-configuration` 主规格
- [x] 3.2 运行 `mvn -pl self-analyst-app,self-analyst-events -am '-Dsurefire.failIfNoSpecifiedTests=false' test`，确认提示词与事件模块测试通过
