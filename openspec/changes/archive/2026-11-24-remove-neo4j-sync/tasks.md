## 1. 移除 `self-analyst-ontology` 中的 Neo4j 模块代码与依赖

- [x] 1.1 删除 `self-analyst-ontology/src/main/java/com/selfanalyst/ontology/Neo4jSyncService.java`、`Neo4jSyncConfig.java`、`Neo4jSnapshotWriter.java` 及其对应测试 `Neo4jSyncServiceTest.java`、`Neo4jSyncConfigTest.java`、`Neo4jSnapshotWriterTimeoutTest.java`、`Neo4jSnapshotWriterIntegrationTest.java`、`Neo4jLuceneCompatibilityTest.java`，验证源码与测试目录下不再存在 `Neo4j*` 文件
- [x] 1.2 从 `self-analyst-ontology/src/main/java/com/selfanalyst/ontology/Ontology.java` 移除 `ExportSnapshot` 记录，从 `OntologyService.java` 移除 `exportSnapshot()` 方法，并清理 `OntologyServiceTest.java` 中依赖 `ExportSnapshot` / `Neo4jSnapshotWriter` 的测试代码，验证 `self-analyst-ontology` 内无 `neo4j` 或 `ExportSnapshot` 引用
- [x] 1.3 从 `self-analyst-ontology/pom.xml` 移除 `neo4j-java-driver-all`、`neo4j-harness`、测试期 `lucene-*`（9.11.1）依赖及 `<neo4j-lucene.version>` 属性，运行 `mvn -pl self-analyst-ontology test` 验证模块编译与单元测试全部通过

## 2. 移除 `self-analyst-app` 后端 Neo4j 配置与 HTTP 接口

- [x] 2.1 删除 `self-analyst-app/src/main/java/com/selfanalyst/config/Neo4jConfigResolver.java` 与 `self-analyst-app/src/test/java/com/selfanalyst/config/Neo4jConfigResolverTest.java`，并在 `application.properties`、`SupportedKeys.java`、`ConfigPolicy.java`、`TomlSupport.java`、`ConfigResolver.java`、`ConfigApplicationService.java` 中移除全部 `neo4j.*` 键、`ConfigPolicy.NEO4J`、`neo4j` 组件及 `next_manual_sync` 状态
- [x] 2.2 删除 `self-analyst-app/src/main/java/com/selfanalyst/desktop/controller/DesktopNeo4jController.java` 与 `self-analyst-app/src/test/java/com/selfanalyst/events/DesktopNeo4jHttpTest.java`，在 `DesktopServer.java` 中移除 `neo4jSync` 字段、初始化与控制器注册
- [x] 2.3 更新 `DesktopOntologyHttpTest.java`（移除 `/neo4j/status` 断言）并在配置测试中补充回归测试，验证旧 `config.toml` 中残留合法 `neo4j.*` 键时不阻塞加载与保存且在 `unknownKeys` 中报告；运行 `mvn -pl self-analyst-app -am '-Dtest=Config*Test,DesktopOntologyHttpTest' '-Dsurefire.failIfNoSpecifiedTests=false' test` 验证通过

## 3. 移除桌面 UI 的 Neo4j 同步页签、资源与前端测试

- [x] 3.1 删除 `self-analyst-app/src/main/resources/desktop-ui/neo4j-sync.js` 与 `self-analyst-app/src/test/js/neo4j-sync.test.mjs`，从 `index.html` 移除 `data-config-view="neo4j"` 页签按钮与 `neo4j-sync.js` 脚本标签
- [x] 3.2 清理 `ui.js`（视图白名单、`mountNeo4jSync`/`refreshNeo4jStatus`/`stopNeo4jSync` 调用）、`config.js`（保存后刷新 Neo4j 状态调用）、`config-layout.css`（`.config-neo4j*` 样式）以及 `locales/zh.json` 和 `locales/en.json` 中全部 `neo4j.*` 与 `config.runtime.next_manual_sync` 翻译键
- [x] 3.3 更新 `self-analyst-app/src/test/js/llm-settings.test.mjs` 和 `ontology.test.mjs`，将原 Neo4j 页签断言替换为“配置窗口不含 Neo4j 页签且不加载 Neo4j 脚本”的断言，运行桌面 UI Node 测试验证通过

## 4. 清理文档、同步规格与全量验证

- [x] 4.1 删除 `docs/neo4j-sync.md`，同步清理 `docs/README.md`、`docs/architecture.md`、`docs/personal-ontology.md`、`README.md`（保持纯英文）和 `README.zh-CN.md`（保持简体中文）中的 Neo4j 说明，核对 diff 确认历史发布日志 `docs/releases/` 未被改动且中英文 README 语言纯净一致
- [x] 4.2 全仓检索确认除历史归档/发布日志及 OpenSpec 变更目录外不再残留 `neo4j` 代码、配置或现行文档引用，运行 `mvn test` 验证所有 Java 与 Node 测试套件全部通过
- [x] 4.3 运行 `openspec validate remove-neo4j-sync --strict`，同步相关主规格（移除或更新 `neo4j-sync`、`user-configuration`、`personal-ontology`、`desktop-help`）并完成归档前核对
