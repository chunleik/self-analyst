## 1. 回归与后台摘要

- [x] 1.1 在 DesktopSummaryAssemblerTest 与摘要 HTTP 测试补充持续标题变化、慢调用、并发、失败退避、重启与关闭场景，验证原实现的重复调用问题可复现。
- [x] 1.2 实现摘要后台批次、独立模型租约、60 秒完成时限、5 分钟准入及持久失败退避；用针对性 Java 测试验证当前事实、Wiki、引用配对和预算降级保持可用。

- [x] 1.3 修正 MergedStorageMigrationTest 固定月份路径，按写入回执的 receivedAt UTC 月份获取分区；运行该测试及全量 mvn test 验证。

- [x] 1.4 将 feature 隔离的 titlebar-review 源码移出 src/bin 并更新 Cargo 显式路径；通过可选 target 编译及正式 NSIS 安装包构建验证。

## 2. 计量与前端

- [x] 2.1 为 UsageMeter、plain/Agent/compaction 回退添加用量来源及旧文件兼容；通过 UsageMeterTest、PlanMiddlewareBudgetTest、UsageMeteredModelTest 和真实 HTTP 模型故障测试验证至多一次记账。
- [x] 2.2 实现前端隐藏暂停、恢复刷新和共用进行中摘要请求；通过 summary-load.test.mjs 验证隐藏、慢响应与错误不清空快照。
- [x] 2.3 在桌面用量入口展示真实、估算、失败预留和旧未分类记账；通过 Node 用量展示与双语测试验证文案及安全降级。

## 3. 验证与交付

- [x] 3.1 同步双语 README、用户说明和相关主规格；运行针对性测试、全量 mvn test 及 OpenSpec 严格校验并记录结果。
- [x] 3.2 使用 openspec-sync-specs 与 openspec-archive-change 同步并归档；确认产物完整、任务完成及生产历史文件未被回写。
- [x] 3.3 构建可安装桌面程序，通过功能分支提交、推送和 PR 交付，确认最新提交的必需检查（包括 Windows 全量验证）通过；记录 PR 与构建位置。
