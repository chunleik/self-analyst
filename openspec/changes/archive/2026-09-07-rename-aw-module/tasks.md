## 1. Maven 模块与包目录

- [x] 1.1 用 `git mv` 将 `self-analyst-aw/` 改为 `self-analyst-events/`，更新该模块 `pom.xml` 的 artifactId，以及根 `pom.xml`、`self-analyst-app/pom.xml`、`self-analyst-wiki/pom.xml` 中的 module/依赖；`mvn -pl self-analyst-events -am -DskipTests package` 能解析新模块
- [x] 1.2 用 `git mv` 将 `com/selfanalyst/aw` 源码与测试包目录改为 `com/selfanalyst/events`，并批量更新 `package` 声明与全仓库 `import com.selfanalyst.aw`；编译不再引用旧包名

## 2. 类型与 Agent 工具

- [x] 2.1 将 `AwServer` 重命名为 `EventServer`（含 `createAndStart` 等工厂方法），测试类 `AwServer*Test` 同步改名；`AppSession` 等持有字段改为 `eventServer`；针对性测试 `EventServerInitializationOrderTest` 通过
- [x] 2.2 将 `ActivityWatchTools` 重命名为 `EventQueryTools`，测试类同步改名；五个 `@Tool` 方法名保持不变，描述改为事件桶/投影查询用语；`SelfAnalystAgent` 注册新类型；跑 `EventQueryToolsTest` 与 Agent 注册相关测试

## 3. 规格、文档与残留搜索

- [x] 3.1 将本 change 的 delta 同步到 `openspec/specs/activitywatch-tools`、`agent-runtime`、`agent-context-compaction` 主规格；`activitywatch-tools` 的 Purpose 可改为描述兼容 HTTP 上的事件查询工具，但目录名与 `SPEC-AW-*` ID 不变
- [x] 3.2 更新 `README.md`、`CONTRIBUTING.md`、`AGENTS.md`、`docs/architecture.md`、`docs/README.md`、`docs/testing.md` 及 CI 工作流中的模块名/类型名；不改 `docs/archive/` 与 `openspec/changes/archive/`
- [x] 3.3 全仓库搜索确认工作区（排除 archive）不再出现 `self-analyst-aw`、`com.selfanalyst.aw`、`class AwServer`、`ActivityWatchTools` 作为现行标识
- [x] 3.4 运行 `mvn -pl self-analyst-events,self-analyst-wiki,self-analyst-app -am '-Dsurefire.failIfNoSpecifiedTests=false' test`，确认跨模块测试通过
