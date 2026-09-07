# Tasks

capability 重命名由归档阶段应用 delta 完成，不作为实施任务；下列任务全部完成后再执行归档。
任务 2.3 会先把链接指向归档后才存在的 `event-query-tools`，这是预期的短暂状态。

## 1. 主规格 Purpose 手工修订

delta 不覆盖 `## Purpose`，需直接编辑主规格。

- [x] 1.1 把 `openspec/specs/content-event-persistence/spec.md` 的 Purpose 中「到 ActivityWatch 持久化」
      改为「到事件服务持久化」，验证该文件不再含品牌名
- [x] 1.2 把 `openspec/specs/raw-event-retention/spec.md` 的 Purpose 中「为 ActivityWatch 时间线」
      改为「为事件时间线」，验证该文件仅剩 SPEC-RAW-008 中三处指代第三方外部模式的引用

## 2. 用户文档统一

- [x] 2.1 更新 `docs/architecture.md` 数据流图（第 11、14 行）与 `AppSession` 启动顺序（第 62 行）中
      指代自研投影的 ActivityWatch，改为事件服务用语，验证该文件不再含品牌名
- [x] 2.2 更新 `docs/testing.md` 第 26 行集成测试标题与第 40 行临时数据库描述，保留 SPEC-ITEST-AW-001
      的 ID 不变，验证该文件不再含品牌名
- [x] 2.3 把 `docs/README.md` 第 28 行 EventQueryTools 的 spec 链接改为 `event-query-tools`，并把能力表中
      的 `activitywatch-tools` 条目改名后按字母序移到 `desktop-summary` 之后，验证文件内不再出现
      `specs/activitywatch-tools`；该文件的「与 ActivityWatch 的关系」整节保持不变

## 3. 核对

- [x] 3.1 检索非归档路径下剩余的 ActivityWatch，确认只剩以下预期保留项：`README.md` 的外部模式说明、
      `SECURITY.md` 的 `/api/0` 兼容接口、`THIRD-PARTY-NOTICES.md` 的许可与兼容声明、
      `docs/README.md` 的第三方关系整节、`openspec/specs/raw-event-retention` 的外部模式条款、
      `openspec/specs/internationalization` 的 aw-webui，以及 `agent-runtime`、`user-configuration`、
      `web-search` 的品牌名禁令条款
- [x] 3.2 确认 `docs/archive/` 与 `openspec/changes/archive/` 未被修改，验证方式为 `git status` 中
      不出现这两个目录下的文件
- [x] 3.3 运行 `mvn -pl self-analyst-app -am '-Dtest=AgentPromptsTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`，
      确认品牌名守卫测试仍然通过且未被改动
