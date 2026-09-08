# 验证记录

日期：2026-09-08。实施分支：`codex/rename-event-configuration`。

## 功能与配置

- 16 个配置键、15 个环境变量统一切换为新名称；旧名称只保留在诊断、手动更新指引、测试和子进程环境清理中。
- TOML、结构化配置 API、Agent 配置工具、有效配置及重启差异完成同步；标题开关与轮询间隔可以共同保存。
- 旧键、空旧环境变量、被新配置遮蔽的旧环境变量、新旧并存均拒绝；HTTP 返回 400，诊断不回显配置值，用户文件和运行配置保持不变。
- 启动检查已实际接入 latest/all；只读验证原始分区与封存 manifest，失败隔离并阻止应用发布端口，保留原文件。
- 独立进程验证旧配置失败退出，以及新名称继续使用已有原始记录和投影；原始分区 SHA-256 保持不变。

## 自动化验证

使用本机 JDK 21，Maven 使用已配置的阿里云镜像 settings。以下命令均成功：

- 配置定向测试：`mvn -pl self-analyst-app -am '-Dtest=ConfigTest,ConfigResolverTest,ConfigApplicationServiceTest,TomlSupportTest,UserConfigStoreRawTest,DesktopConfigControllerTest,ConfigToolsTest,EventConfigurationStartupTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`。
- 完整性定向测试：`mvn -pl self-analyst-app -am '-Dtest=RawStartupIntegrityTest,EventConfigurationStartupTest,ConfigResolverTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`。
- 全量 `mvn test`：Java 共 636 项，失败 0、错误 0、按原有条件跳过 5 项；Node 96 项全部通过。
- `mvn -pl self-analyst-app -am package '-DskipTests'` 和 `cargo build --release --manifest-path self-analyst-desktop/src-tauri/Cargo.toml`。
- `pwsh -File scripts/check-packaged-jar.ps1`（指定本机 JDK 21 java.exe），验证 fat JAR 启动、认证、端口及退出。构建中重复的 shaded JAR 经 SHA-256 确认与最终 JAR 完全一致后清理，默认产物选择恢复正常。
- `pwsh -File scripts/check-desktop-config-editor.ps1`，PowerShell 脚本语法检查以及 `bash -n scripts/install.sh`。
- `pwsh -File scripts/check-desktop-autostart.ps1 -DistributionPath '.tmp/rename-desktop-stage'`：中文空格路径、不同工作目录、静默自动启动、手动唤起、单一受管 Java、移动目录后启动、故障退出与诊断均通过；临时实例已退出。
- `git diff --check`、`openspec validate rename-event-configuration --strict` 和 `openspec validate --specs --strict`；21 个主规格通过严格校验。

JAR 与桌面壳均来自本轮构建，SHA-256 分别为：

- `self-analyst-app-0.1.0.jar`：`17D1BE2E679EABB0D0469680FEC66DF6D152A4434D77E9E728B39EF8008CCD3B`。
- `SelfAnalyst.exe`：`84279CCA54C0918E7E9AC335A2F35CC1505CD792FF06185419D3A00F034E01E0`。

## 规格与文档

同步 `user-configuration`、`event-query-tools` 和 `raw-event-retention` 三个主规格，新增 SPEC-TOML-RENAME-001..003 与 SPEC-RAW-013，既有 Requirement ID 和场景追溯标题保留。

README 提供完整手动更新对照；docs/README、docs/architecture、docs/benchmarks/raw-event-retention 及 scripts/README 保留为现行用户与架构文档。docs/archive 与既有 OpenSpec 归档保留历史名称，不作为当前配置入口。

未修改真实用户配置、数据目录或全局环境变量。本次完成本地实施、验证和规格归档；提交、推送与 PR 合并尚未执行，后续交付仍需最新提交的远端必需检查全部成功。
