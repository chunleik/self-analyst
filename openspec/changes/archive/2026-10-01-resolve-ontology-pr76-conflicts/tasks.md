## 1. 同步与冲突解决

- [x] 1.1 合入最新 main 并解决 12 个版本配置冲突，保留 0.6.0-beta.1；通过版本检查和 release-channel Node 测试。
- [x] 1.2 合并 DesktopServer 的本体关闭与摘要增强停止逻辑；通过 DesktopOntologyHttpTest、LlmHotReloadIntegrationTest 及仪表盘针对性测试。
- [x] 1.3 复核自动合并的 Agent、AppSession、桌面资源及中英文 README；确认双方功能保留、README 语言一致且 git diff --check 无错误。

## 2. 集成验证

- [x] 2.1 运行 mvn test，确认各模块 Java 与 Node 测试通过。
- [x] 2.2 运行相关 Cargo 锁文件/测试检查与 openspec validate --all --strict；确认无未解决冲突，既有主规格无需额外同步。

## 3. 交付

- [x] 3.1 记录本地验证证据，检查合并结果无未解决冲突、main 与 PR 分支未出现更新，准备归档和合并提交。

所有任务完成后归档本 change，将合并提交推送到 PR #76 原分支；通过远端 head 和 mergeable 状态确认冲突消除并报告最新 CI，不合并 PR。归档与最终推送是任务完成后的交付收尾，避免把归档本身作为归档的前置任务。

## 验证证据（2026-10-01）

- 同步 main bf0188e 到 PR head 911f4c7；13 个冲突文件全部解决。再次 fetch 确认两端未更新。
- `pwsh -NoProfile -File scripts/resolve-release-channel.ps1 -Tag v0.6.0-beta.1` 返回 prerelease，全部工程版本一致。
- 针对性 Maven：OntologyServiceTest、OntologySourcesTest、DesktopOntologyHttpTest、LlmHotReloadIntegrationTest、DesktopSummaryAsyncTest、DesktopSummaryAssemblerTest、RuntimeStorageCompatibilityTest 全部通过。
- `mvn --batch-mode test` 全量 BUILD SUCCESS；Node 216 项全部通过。
- `cargo test --locked --offline --manifest-path self-analyst-desktop/src-tauri/Cargo.toml`：40 项通过；sidecar 同命令测试/编译通过，当前无单元测试。
- 桌面与 sidecar 的 `cargo fmt -- --check` 通过。
- `openspec validate --all --strict`：32 项通过；本 change 严格校验通过。
- `git diff --check`、`git diff --cached --check` 通过，Git 无未解决冲突。
- 自动合并复核：本体与 main 的摘要增强停止调用、模型热切换工具、语言重启资源、双语 README 均保留；现有主规格契约不变，无增量规格需同步。
- docs/ 下本体指南、验证文档及预发布说明保持原用途；main 合入的语言重启与看板截图仍用于既有功能说明，无历史文档需要删除。
- 远端 CI 以最终推送后的 PR head 为准，不使用旧提交检查结果；本次不合并 PR 或发布版本。
