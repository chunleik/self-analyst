## Context

参见 `proposal.md` 的背景与动机。当前 Neo4j 手工同步横跨 `self-analyst-ontology`（导出快照模型、同步服务、Bolt 写入器与 Neo4j/Lucene 9 依赖）、`self-analyst-app`（配置白名单/策略/解析器、HTTP 控制器、桌面前端页签与国际化资源）以及多份用户/架构文档和 OpenSpec 主规格。本次移除需要确保跨模块清理彻底、不残留死代码或无用依赖，同时不破坏现有用户的旧 `config.toml` 启动和本地知识本体功能。

## Goals / Non-Goals

**Goals:**
- 彻底移除 Neo4j 的配置定义、运行时代码、HTTP 路由、桌面 UI、国际化键、Maven 依赖、专属测试和现行文档。
- 保证旧 `config.toml` 中已存在的合法 `neo4j.*` 键不会阻断升级用户启动或后续配置保存。
- 保持 `self-analyst-ontology` 的本地本体实体、关系、纠错、重建和查询能力不受影响。

**Non-Goals:**
- 不提供自动改写或清理用户本地 `config.toml` 中残留 `[neo4j]` 段落的迁移脚本。
- 不连接或清理用户此前同步到外部 Neo4j 数据库的历史投影数据。
- 不修改 `docs/releases/` 下已发布版本的历史变更日志。

## Decisions

1. **旧 `neo4j.*` 配置键的兼容策略：按普通未知键处理**
   - **选择**：从 `SupportedKeys`、`ConfigPolicy`、`application.properties`、`TomlSupport` 和 `ConfigApplicationService` 中直接移除 `neo4j.*` 相关定义与特判，不将其加入 `DeprecatedKeys`（即不强制拒绝启动/保存）。
   - **理由**：与此前移除 `aw.ocr.*`、`aw.audio.*` 等退役功能键的既有策略一致（见 `SPEC-TOML-RENAME-002`）。用户升级后即使 `config.toml` 中仍留有 `[neo4j]`，应用也能正常启动；在 raw 编辑器保存时会逐字保留原文并在 `unknownKeys` 中提示，结构化保存则忽略未知键。
   - **替代方案**：将 `neo4j.*` 加入强拒绝清单并阻断启动。该方案会导致升级用户无法直接启动应用，体验差且没有对应替代键可迁移，因此不采用。

2. **同时移除 `Ontology.ExportSnapshot` 与 `OntologyService.exportSnapshot()`**
   - **选择**：经全仓检索，`Ontology.ExportSnapshot` 和 `OntologyService.exportSnapshot()` 仅被 `Neo4jSyncService`、`Neo4jSnapshotWriter` 及其测试引用，且其容量上限异常消息直接硬编码为 `neo4j.snapshotTooLarge`。将其一并删除，避免在 `self-analyst-ontology` 中保留无调用方的死代码。
   - **替代方案**：保留 `ExportSnapshot` 仅重命名异常消息。因当前无其他导出消费方，保留会增加维护负担，不采用。

3. **清理 `self-analyst-ontology/pom.xml` 中为 Neo4j 引入的全部依赖**
   - **选择**：移除 `org.neo4j.driver:neo4j-java-driver-all`、`org.neo4j.test:neo4j-harness` 以及仅服务于 `neo4j-harness` 测试环境的 `lucene-core`/`lucene-analysis-common`/`lucene-queryparser`（9.11.1）与 `<neo4j-lucene.version>` 属性。
   - **理由**：`self-analyst-ontology` 自身仅依赖 Jackson、SQLite JDBC 和 JUnit 5，移除后可消除不必要的传递依赖与打包体积。

4. **HTTP 路由直接移除**
   - **选择**：删除 `DesktopNeo4jController` 及其在 `DesktopServer` 中的注册，不再保留 `/desktop/ontology/neo4j/status` 和 `/desktop/ontology/neo4j/sync` 路由（未匹配路径按框架默认 404 返回）。

## Risks / Trade-offs

- **风险：旧 `config.toml` 若包含原本就会被 `Neo4jConfigResolver.rejectStoredSecrets` 拦截的 `neo4j.password` 键，移除后不会再报错** → **缓解**：该键已不再被任何组件读取或外发，按普通未知键在 `unknownKeys` 中提示即可，用户可自行清理；现有 `llm.api-key` 等仍由 `ConfigResolver.sensitive` 负责脱敏。
- **风险：其他测试可能间接断言配置页签数量、模板文本或 `application.properties` 键数** → **缓解**：在实施阶段先运行配置、本体和桌面 UI 的针对性测试，再运行全量 `mvn test` 核对所有断言。
- **风险：英文 `README.md` 与中文 `README.zh-CN.md` 同步清理时可能遗漏段落或混入语言** → **缓解**：修改后逐段核对 diff，确保英文版保持纯英文、中文版保持简体中文且双方信息一致。
