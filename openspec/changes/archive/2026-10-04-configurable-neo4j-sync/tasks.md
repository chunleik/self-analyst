## 1. 快照与同步器

- [x] 1.1 增加最终图快照与稳定合并身份，OntologyServiceTest 验证纠错、失效和不可变性
- [x] 1.2 增加安全连接配置与 Neo4j 同步器，单元测试验证关闭、缺配置、URI、预算、参数化和错误脱敏
- [x] 1.3 用合成数据的 Neo4j 集成测试验证重复同步、更新、拒绝、删除、命名空间隔离与失败回滚

## 2. 应用入口

- [x] 2.1 配置登记及 API 状态、确认和手工触发，Java 控制器测试验证无隐式网络、取消、防重复和确认目标绑定
- [x] 2.2 知识 UI 的双语范围披露及手工确认，Node 测试验证取消、重复点击和错误状态

## 3. 文档和验证

- [x] 3.1 同步中英文 README、中文指南与隐私说明，检查无真实密钥且说明本地权威和远端清理语义
- [x] 3.2 运行针对性测试、跨模块 mvn test、Node 测试和 OpenSpec strict 校验，逐项记录通过与环境阻断
- [x] 3.3 同步主规格并按 OpenSpec 归档，生成可审阅本地提交/补丁；不推送、发布或部署

## 验证记录

- 2026-10-04 Linux / Java 21：完整 `mvn test` 成功，Java 1025 通过 / 4 跳过 / 0 失败，桌面 Node 200 通过 / 31 跳过 / 0 失败。
- 本体 28 个测试全通过，包含认证开启、仅环回的 Neo4j 合成实例及客户端截止时间/清理门禁测试。
- OpenSpec `validate --all --strict` 与 `git diff --check` 通过；最终归档后再次检查。
- 未运行真实远端 TLS、原生 Windows 桌面或远端 CI；云浏览器阻止 localhost 预览，未取得 UI 截图。
- 实现验收阶段只生成本地功能分支与补丁；后续经用户明确授权提交 GitHub 并创建 Draft PR，不合并、发布、部署或同步真实数据。
- 保留的用户文档：`docs/neo4j-sync.md`、`docs/personal-ontology.md`、`docs/architecture.md`、`docs/README.md`；它们为当前用户指南/架构说明，非第二套历史规格。
- `mvn package -DskipTests` 成功，打包 JAR 默认关闭服务及隔离驱动加载通过；未请求真实连接。
