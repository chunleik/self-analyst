## 1. 复现与修复

- [x] 1.1 在 PR #38 的现有工作树确认最新 head，同步 `origin/main`；通过 `git status`、提交关系和 diff 确认保留 README 翻译及无关文件。
- [x] 1.2 在 `self-analyst-app/src/test/java/com/selfanalyst/agent/LlmHotReloadIntegrationTest.java` 增加夹具关闭回归测试：记录已知用量，断言关闭后最终计数持久化、计量后台执行器终止；先运行并记录修复前失败证据，测试自身兜底清理，禁止固定睡眠。
- [x] 1.3 修复同文件夹具关闭顺序：Agent 收敛后调用 `meter.flush()`，保证 HTTP 服务和请求执行器在异常时也被清理并等待终止；运行新增回归测试及原取消场景，确认通过。

## 2. 验证与交付

- [x] 2.1 使用 JDK 21 执行 `mvn -pl self-analyst-app -am '-Dtest=LlmHotReloadIntegrationTest,UsageMeterTest,SelfAnalystAgentOpenAiCompatibilityTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`，确认全部通过并记录结果。
- [x] 2.2 执行 `mvn test` 与 `openspec validate fix-llm-hot-reload-test-cleanup --strict`，记录结果；复核不涉及业务契约变更，确认无需同步主规格或修改用户文档。
- [x] 2.3 将测试修复及 OpenSpec 产物提交并推送到 PR #38 功能分支，更新 PR 标题和说明以覆盖最终变更、change 名称及验证结果；检查 PR 最新 head 的必需检查全部成功，包含 Windows 全量验证。
- [x] 2.4 完成验证后按归档技能归档 change，并提交推送归档产物；再次确认最终 head 检查成功，汇报 PR 状态，未获用户合并授权时不合并。

## 验证记录

- 2026-09-14：已同步 main 的 f236711，README 翻译保留。
- 修复前：新增 fixtureCloseFlushesUsageAndStopsBackgroundWork 测试失败，断言证实夹具关闭后计量执行器未终止；1 项失败、0 项异常。
- 修复后：LlmHotReloadIntegrationTest 11 项、UsageMeterTest 14 项、SelfAnalystAgentOpenAiCompatibilityTest 1 项全部通过；桌面 Node 测试 124 项通过。
- JDK 21 下完整 mvn -B test 通过，全部 Maven 模块成功；Windows 本机用时约 66 秒。
- openspec validate fix-llm-hot-reload-test-cleanup --strict 及 openspec validate --all --strict 通过（26 项）。
- 本次只修改测试夹具和回归测试，主规格及 docs/ 用户文档无需变更，现有文档保留。
- 修复提交 63c58d012cd9fa7c63610d434167f70cc7c32bfd 的 GitHub Actions 34817548205 全部通过，Windows 全量验证用时 11 分 55 秒；PR 状态为 CLEAN。
- 归档提交 e1f82ca7521825710a77e7d0b7e5b23d8af5861a 的 GitHub Actions 34818619738 全部通过，Windows 全量验证用时 11 分 43 秒；归档和推送已完成，PR 保持开放，未执行合并。
