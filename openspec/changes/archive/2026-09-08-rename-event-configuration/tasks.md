## 1. 实施准备与契约用例

- [x] 1.1 确认并使用 `codex/` 功能分支，检查已有工作区修改和本 change 规划文件；以 `git status --short`、`git branch --show-current` 及 `openspec status --change rename-event-configuration --json` 记录实施起点。
- [x] 1.2 在 `ConfigResolverTest`、`ConfigTest` 中补充 16 项新键与 15 项环境变量的行为用例，覆盖 TOML 优先、删除覆盖兜底、端口无环境入口、标题开关/间隔及 startupScope；验证不同输入确实改变对应有效配置，默认值保持一致。
- [x] 1.3 在 `TomlSupportTest`、`UserConfigStoreRawTest` 中补充标题 enabled 与 pollMs 同时保存、完整模板可解析、表内/顶层写法和中文路径往返用例；验证生成 TOML 不产生标量/表冲突且保留等价覆盖值。

## 2. 配置声明、解析与旧名拒绝

- [x] 2.1 更新 `SupportedKeys`、`application.properties`、`ConfigResolver`、`Config`、`TomlSupport` 的新名称、环境映射与模板分组；将 Java 配置访问器及应用/查询服务调用按 design.md 同步更名。通过任务 1.2、1.3 的定向测试验证声明、运行值和生成结果一致。
- [x] 2.2 为本次移除的有限键/环境名单实现共享诊断，在启动有效值解析及三种保存入口过滤前接入；补充旧键、空旧值、新旧并存、被 TOML 遮蔽的旧环境、注释不误判用例，验证错误只含名称与来源、不含值，且不自动迁移或读取旧值。
- [x] 2.3 更新 `ConfigPolicy`、`RawConfigValidator`、`RawIntegrityPolicy`、`ConfigApplicationService` 和控制器目录校验，使用新键和候选有效路径；通过 `ConfigApplicationServiceTest`/控制器测试验证已有分区后的目录保护、父目录间接变化、磁盘阈值及 latest/all 校验，并确认事件配置仍归入 events 重启组件。
- [x] 2.4 保留 `DeprecatedKeys` 的旧 OCR/音频忽略语义，同时拒绝两种前缀下禁止的 raw 保留策略键；增加回归用例，验证旧废弃功能没有复活、未被误当本次改名项，未知普通键仍按原规则处理。

- [x] 2.5 将 startupScope 接入 EventServer/RawEventStore，增加只读分区及封存 manifest 检查、catalog 最近校验时间和失败隔离；通过 RawStartupIntegrityTest 验证 latest/all、空目录、缺失/不一致文件及原文件保持不变，再通过 EventConfigurationStartupTest 验证应用阻止失败启动和端口发布。

## 3. 桌面配置与 Agent 入口

- [x] 3.1 更新 `DesktopConfigController` 的 events 分组、collection.title.enabled/pollMs 映射和旧结构化输入诊断；通过 `DesktopConfigControllerTest` 验证新 JSON 保存/读取、旧 aw 分组或 collection.content 返回 400，失败不写盘且不改变运行版本。
- [x] 3.2 更新 raw/effective 配置元数据、inheritedFrom、restartRequired 和组件差异中的键名；通过 `ConfigApplicationServiceTest` 与控制器测试验证端口派生来源、修改需重启、恢复运行值后清除差异，响应只把新键列为受支持项。
- [x] 3.3 更新 `ConfigTools` 的可写键映射、描述和返回，保留原先权限范围；通过 `ConfigToolsTest` 验证新标题开关可写、旧名明确失败、其它原本不可写配置没有被扩大授权。
- [x] 3.4 将带 TOML 文本的 LLM/Embedding 测试入口接入同一旧名诊断，确保此类错误返回 400；通过控制器测试和相关 Node 配置测试验证不发起远端请求、不写文件，UI 展示错误且保留编辑文本与脏态，正常模型测试行为不变。

## 4. 启动脚本、文档与存储回归

- [x] 4.1 更新 `scripts/check-packaged-jar.ps1`、`scripts/check-desktop-autostart.ps1` 的 TOML 和子进程环境，隔离继承的旧变量并使用新名称关闭所有采集；检查只修改子进程环境及临时配置，通过脚本语法检查和后续任务 5.2 的冒烟验证。
- [x] 4.2 更新 `scripts/install.ps1`、`scripts/install.sh` 中的 SelfAnalyst 配置示例及 `.github/ISSUE_TEMPLATE/bug_report.yml`，保留第三方 AW_INSTALLED/AW_URL/start-aw 脚本语义；用定向残留扫描与脚本语法检查确认仅修改本产品配置名称。
- [x] 4.3 更新 `README.md`、`docs/README.md`、`docs/architecture.md`、`docs/benchmarks/raw-event-retention.md`、`scripts/README.md`，提供完整手动改名指引；按增量规格逐项核对新键/环境名称、端口例外及标题 TOML 示例，归档文档保持不动。
- [x] 4.4 在隔离启动回归中验证旧 TOML/旧环境导致非零退出，且事件目录、采集器与监听服务未初始化；将已存在原始分区的目录配入新名称后验证继续读取既有记录、文件位置不变，不因更名迁移或重建投影。测试仅使用临时数据和关闭采集的独立进程。

## 5. 集成验证与规格同步

- [x] 5.1 运行定向验证：`mvn -pl self-analyst-app -am '-Dtest=ConfigTest,ConfigResolverTest,ConfigApplicationServiceTest,TomlSupportTest,UserConfigStoreRawTest,DesktopConfigControllerTest,ConfigToolsTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`；确认新命名、旧名拒绝、目录保护和配置入口用例全部通过，再运行跨模块 `mvn test`，包含事件查询、原始层、Java 和桌面 UI Node 回归。
- [x] 5.2 对本次产物执行 `mvn -pl self-analyst-app -am package '-DskipTests'`、`pwsh -File scripts/check-packaged-jar.ps1` 及 `pwsh -File scripts/check-desktop-config-editor.ps1`；修正编辑器静态检查将操作栏调用后的分号位置写死的既有断言，继续验证操作栏位于编辑器前。准备匹配版本的桌面便携产物后运行 `pwsh -File scripts/check-desktop-autostart.ps1`，验证新配置启动、非默认端口握手、静默入口与退出均成功，记录实际产物和结果。
- [x] 5.3 扫描受影响源码、资源、脚本及现行文档中的完整旧键、旧环境名和旧 Java 访问器，将残留逐项限定为诊断表、负向测试、手动改名说明、废弃功能或真实第三方引用；以 `git diff --check` 和逐项核对记录确认没有误伤 raw 字符串、协议、数据标识及无关配置。
- [x] 5.4 使用 `openspec-sync-specs` 同步 user-configuration、event-query-tools、raw-event-retention 三个主规格，保持稳定 Requirement ID；运行 `openspec validate rename-event-configuration --strict` 和 `openspec validate --specs --strict`，确认任务与验证证据齐全，满足 `openspec-archive-change` 的归档前置条件。后续 PR 交付按仓库要求等待最新提交的必需检查（含 Windows 全量验证）成功后再按授权合并。
