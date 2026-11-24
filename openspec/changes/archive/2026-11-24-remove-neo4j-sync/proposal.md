## Why

Neo4j 手工图投影同步是早期引入的可选外部集成，增加了配置面、桌面设置页签、HTTP 接口、驱动及测试依赖（包括 `neo4j-java-driver-all`、`neo4j-harness` 与独立的 Lucene 9 测试依赖），但当前产品已不再需要该功能。彻底移除 Neo4j 的配置、功能与代码可以简化个人知识与配置体验、收紧本地隐私边界并降低构建与维护成本。

## What Changes

本次变更为明确的功能移除与行为修改（非纯文档基线迁移）：

- **BREAKING** 移除整个 `neo4j-sync` 能力及相关后端实现：删除 `self-analyst-ontology` 中的 `Neo4jSyncService`、`Neo4jSyncConfig`、`Neo4jSnapshotWriter`、专用于 Neo4j 导出的 `Ontology.ExportSnapshot` 与 `OntologyService.exportSnapshot()`，以及 `self-analyst-ontology/pom.xml` 中的 Neo4j 驱动、嵌入式测试库和配套 Lucene 9 测试依赖。
- **BREAKING** 移除桌面后端 Neo4j HTTP 接口（`GET /desktop/ontology/neo4j/status`、`POST /desktop/ontology/neo4j/sync`）与 `DesktopNeo4jController`，以及 `DesktopServer` 中的 Neo4j 服务装配。
- **BREAKING** 移除全部 `neo4j.*` 受支持配置键（`neo4j.enabled`、`neo4j.uri`、`neo4j.database`、`neo4j.username`、`neo4j.password-env`、`neo4j.namespace`、`neo4j.timeout-seconds`）、`application.properties` 默认值、`ConfigPolicy.NEO4J`、`Neo4jConfigResolver`、`TomlSupport` 中的 `neo4j.` 分组以及有效配置/保存结果中的 `neo4j` 组件与 `next_manual_sync` 状态。旧 `config.toml` 中残留的合法 `neo4j.*` 键按现有普通未知键规则处理（不生效、不阻塞启动、raw 保存时保留原文并在 `unknownKeys` 报告）。
- **BREAKING** 移除桌面配置窗口中的“Neo4j 同步”页签、`neo4j-sync.js`、`config-layout.css` 中的 `.config-neo4j*` 样式、`index.html`/`ui.js`/`config.js` 中的挂载与调用以及 `locales/zh.json` 和 `locales/en.json` 中的 `neo4j.*` 与 `config.runtime.next_manual_sync` 翻译键。
- 更新与删除相关测试及用户/架构文档：移除 Neo4j 专属 Java/Node 测试，调整受影响的本体、配置与桌面 UI 测试；删除 `docs/neo4j-sync.md`，同步清理 `README.md`、`README.zh-CN.md`、`docs/README.md`、`docs/architecture.md` 和 `docs/personal-ontology.md` 中的 Neo4j 说明（历史发布记录 `docs/releases/` 保持原样）。

## Capabilities

### New Capabilities
- 无新增能力。

### Modified Capabilities
- `neo4j-sync`: 移除 `SPEC-NEO-001`、`SPEC-NEO-002`、`SPEC-NEO-003` 全部需求，下线 Neo4j 图投影同步能力。
- `user-configuration`: 从 `SPEC-CFG-LIVE-002` 移除 Neo4j 有效配置与“下次手工操作生效”语义；从 `SPEC-CFGUI-LAYOUT-003` 移除配置窗口的“Neo4j 同步”页签及对应场景；明确 `neo4j.*` 不再属于受支持键。
- `personal-ontology`: 更新 `SPEC-ONTO-011`，移除“可选 Neo4j 同步”外发例外，明确完整图谱不提供外发通道。
- `desktop-help`: 更新 `SPEC-HELP-DOCS-001`，移除“个人知识指南”场景中对 Neo4j 同步说明的引用。

## Impact

- **Maven 依赖与模块**：`self-analyst-ontology/pom.xml` 移除 `neo4j-java-driver-all`、`neo4j-harness` 和测试期 `lucene-*` 9.x 依赖。
- **后端 Java 代码**：`self-analyst-ontology`（`Neo4j*`、`Ontology.java`、`OntologyService.java`）、`self-analyst-app`（`DesktopNeo4jController.java`、`DesktopServer.java`、`Neo4jConfigResolver.java`、`SupportedKeys.java`、`ConfigPolicy.java`、`ConfigResolver.java`、`ConfigApplicationService.java`、`TomlSupport.java`、`application.properties`）。
- **桌面前端与国际化**：`self-analyst-app/src/main/resources/desktop-ui/`（删除 `neo4j-sync.js`，更新 `index.html`、`ui.js`、`config.js`、`config-layout.css`、`locales/zh.json`、`locales/en.json`）。
- **测试套件**：删除 `Neo4j*Test.java`、`Neo4jConfigResolverTest.java`、`neo4j-sync.test.mjs`；更新 `OntologyServiceTest.java`、`DesktopOntologyHttpTest.java`、`llm-settings.test.mjs`、`ontology.test.mjs` 及配置相关测试。
- **文档与规格**：删除 `docs/neo4j-sync.md`；更新 `README.md`、`README.zh-CN.md`、`docs/README.md`、`docs/architecture.md`、`docs/personal-ontology.md` 及 `openspec/specs/` 对应主规格。
