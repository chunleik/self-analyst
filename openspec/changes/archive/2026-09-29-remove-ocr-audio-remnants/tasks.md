## 1. 清理实现与文档

- [x] 1.1 删除 DeprecatedKeys 与 DesktopConfigController 的旧采集配置特例，配置测试验证未知键结果。
- [x] 1.2 删除 ContentEventPolicy 与 ContentEventV2Migration 的旧计数和来源支持，策略与迁移回归测试验证拒绝与标题回退。
- [x] 1.3 清理模块描述、恢复文档和双语 README 等活跃入口，检索确认无悬空链接和过时支持声明。

## 2. 验证与规格收尾

- [x] 2.1 运行针对性 Maven 测试及 mvn test，记录结果并确认无无关文件修改。
- [x] 2.2 同步三个主规格，通过 openspec validate --strict 后归档本 change。

## 验证记录

- Java 21，使用本机公共镜像配置 `-s C:/Users/10478/.m2/settings-aliyun.xml`。
- 针对性测试：`mvn -pl self-analyst-app -am '-Dtest=ConfigTest,ConfigResolverTest,DesktopConfigControllerTest,ContentEventPolicyTest,ContentEventV2MigrationTest,SummaryServiceTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`：85 个 Java 测试及 199 个 Node 测试通过。
- `mvn test`：全部模块 BUILD SUCCESS，199 个 Node 测试通过。
- `openspec validate remove-ocr-audio-remnants --strict` 与 `openspec validate --specs --strict` 通过，30 份主规格有效；三个增量均已逐项核对同步。
- `git diff --check` 通过，README 双语仅同步删除旧入口，无语言混入；活跃文档中无恢复指南悬空链接。
- 保留 `docs/archive/design-proposals/`、`docs/archive/legacy-specs/` 和 `docs/releases/` 的历史记录，后续仍仅作追溯；保留隐私文档及架构文档的现行边界说明。构建脚本的旧工具清理用于防止发布污染，未删除。
- 未改动无关的 `speed-up-windows-full-verification` change，也未操作用户数据库或历史文件。