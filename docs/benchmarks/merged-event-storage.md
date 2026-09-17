# 合并事件存储验证记录

日期：2026-09-17。对应变更：`adopt-merged-event-storage`。全部实验使用 JUnit 临时目录与合成数据，未迁移或清理用户运行数据。

## 容量样本

固定标题与应用，100,000 条心跳、间隔 500 毫秒、每条 duration=2 秒。新存储仅产生一个活动区间。
时钟推进超过 24 小时后清理幂等回执，再模拟后续两天各 1,000 条心跳，验证回执页复用与活动区间连续性。
容量读取在数据库连接关闭、WAL 已 checkpoint 后进行，不以 VACUUM 隐藏回执分配过的页面。

| 项目 | 字节 | 约 MiB |
|---|---:|---:|
| 新库（含已分配、可复用的回执页） | 10,149,888 | 9.7 |
| 旧 raw 分区及永久来源映射基线 | 60,534,784 | 57.7 |

新库为旧基线的 16.77%，达到该固定样本不超过 20% 的门槛。旧 raw 使用原写入实现生成，来源映射使用原 schema 与同一组事件 ID 批量填充；该实验比较容量，不比较旧版吞吐。真实标题变化、UUID 长度、采集时长和回执峰值不同，不承诺用户目录同比例缩小。

## 验证命令与覆盖

- `mvn test -q`：完整 Maven 回归通过，包含 151 项 Node 测试。
- `mvn -pl self-analyst-events -am '-Dtest=MergedStorageMigrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test -q`：补充 Windows 目录联接、清理范围和清理重试测试通过。
- 固定时钟验证合并、乱序、不缩短区间、跨日回执清理和重启幂等；触发器模拟事务失败，验证整批回滚。
- 迁移覆盖复制/校验/准备/替换阶段中断、空间不足、未知或损坏 schema、权威表缺失、待处理尾部、删除歧义、外置 raw 和活动库清理保护。
- 根格式提交测试在发布版本 2 后注入切换前失败，验证旧活动文件未被替换且新程序能够恢复 prepared 状态。
- 界面使用实际 `runtime-storage.js` 和项目 CSS，以合成状态检查；[组件预览截图](../screenshots/merged-storage-preview.png) 不来自用户实际数据库。

## 运维边界

当前事件库是权威数据，旧 raw 查询及新原始导出已退役。旧备份在明确确认前保留，整个目录可能暂时比升级前更大。
旧永久层报告 `raw-event-retention.md` 仅保留历史容量证据。原永久层主规格连同 Purpose 已退役，SPEC-RAW-001～013 可在历史 change 与 Git 中追溯；在本仓库检出中可用 `git show HEAD:openspec/specs/raw-event-retention/spec.md` 查看提交前版本，不需要恢复运行数据。
