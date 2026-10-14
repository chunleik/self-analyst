# 验证记录

日期：2026-10-14。分支：`codex/repair-blocked-day-summary`，基于 `origin/main` 的 `5f90adf`。

## 回归和实现

- 修改前运行 `mvn -pl self-analyst-wiki -am '-Dtest=WikiRepairServiceTest#periodBudgetPauseRemainsLocallyRepairable' '-Dsurefire.failIfNoSpecifiedTests=false' test -q`，该回归如预期失败：额度耗尽的 PENDING 小时被判定不可修复。
- 扩展小时、半天、全天 FAILED 和周期额度耗尽 PENDING 的资格；服务端和看板统一核验完整直接下级。半天/全天通过子摘要装配证据，保留用户陈述及引用来源，子周期版本纳入草稿指纹。
- 全天折叠卡片显示带准确范围的下级按钮；详情入口保留，点击不折叠卡片，继续追问不受影响。

## 命令和结果

使用 JDK 21，所有数据库、事件与模型均为临时合成夹具；未修改用户真实数据库、额度或已安装程序，也未调用线上模型。

1. `mvn -pl self-analyst-app -am '-Dtest=WikiRepairServiceTest,WikiAggregateRepairTest,WikiRepairWorkerTest,SummaryTimelineAssemblerTest,SummaryRepairChatServiceTest,DesktopSummaryRepairControllerTest' '-Dsurefire.failIfNoSpecifiedTests=false' test -q`：退出码 0。日志 `target/repair-blocked-targeted.log`。
2. `mvn -pl self-analyst-wiki -am '-Dtest=WikiAggregateRepairTest,WikiRepairWorkerTest' '-Dsurefire.failIfNoSpecifiedTests=false' test -q`：退出码 0，覆盖新增确认来源传递及“半天耗尽 → 本地修复 → 全天继续生成”。日志 `target/repair-blocked-integration.log`。
3. `mvn test -q`：退出码 0，日志 `target/repair-blocked-full.log`。Java 报告汇总零失败、零错误；包含项目既有条件跳过。Node 套件 293 项、292 通过、1 项显式浏览器测试跳过；下一项单独执行该浏览器检查。
4. `node scripts/check-summary-repair-ui.mjs --run`：退出码 0，使用本机无头 Edge。报告 `artifacts/summary-repair-ui/verification.json`，`modelCalls: 0`。首次新断言误读 API 的 `target` 而非实际 `targetId`，已修正并复跑通过；同时以正常 Browser.close 关闭临时浏览器，避免强退导致 profile 文件锁。
5. `openspec validate repair-blocked-day-summary --strict`：通过。
6. `openspec validate --specs --strict`：33 项主规格全部通过。两项增量规格的全部 requirement 已逐块核对与主规格一致。
7. `git diff --check`：通过；双语 README 完整差异已审阅，英文新增说明无中文段落，中文版信息一致。

## 界面证据

- `artifacts/summary-repair-ui/blocked-day-zh.png`
- `artifacts/summary-repair-ui/blocked-day-en.png`

供 PR 审阅的合成截图副本：`docs/mockups/blocked-day-summary-repair-zh.png`、`docs/mockups/blocked-day-summary-repair-en.png`。输出目录本身不入版本库。

已逐张查看上述中英文 680px 合成截图：昨天卡片未展开时按钮可见，准确标注下半天范围，没有横向溢出。脚本实际点击后验证请求 `targetId=synthetic-half`，未调用聊天模型。截图均为合成活动，无真实用户内容。

## 文档和交付边界

已同步 `desktop-summary`、`summary-repair` 主规格。保留并更新 `docs/summary-repair.md` 作为用户操作指南；既有 `docs/runtime-storage.md` 的 schema 5 迁移说明继续有效，本次无新增数据库迁移。双语 README 同步更新。

截至上述实现验证完成时，代码尚未提交、push、创建 PR 或发布安装包；后续交付状态以 Git 提交和 PR 为准。用户安装的 beta.8 不会因本地源码修改自动升级。工作区 `.vscode/` 及原有未跟踪文件保留。
