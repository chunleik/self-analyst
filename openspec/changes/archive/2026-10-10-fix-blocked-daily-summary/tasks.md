## 1. 历史摘要组装

- [x] 1.1 在 SummaryTimelineAssemblerTest 以合成数据复现日→半天→失败小时及错误 incomplete 状态，先运行定向测试确认失败。
- [x] 1.2 实现精确子周期的部分摘要与依赖诊断，覆盖已完成父优先、无重叠、时区／边界／统计版本过滤、缺失／跳过／查询失败和后续完整替换；定向 Java 测试通过且无模型调用、无 Wiki 写入。

## 2. 时间轴与说明

- [x] 2.1 更新 agent.js 和中英文语言资源，默认显示部分／仅统计状态及完整子摘要，详情展示主题、失败原因、额度与重试时间；Node 回归覆盖安全转义、旧数据和展开状态保持。
- [x] 2.2 同步 README.md 与 README.zh-CN.md 的部分历史摘要说明，检查语言及信息一致。

## 3. 验证与收尾

- [x] 3.1 运行相关摘要 Java 测试、完整 Node 测试与 OpenSpec 严格验证，记录结果；以合成页面验证部分摘要可读。
- [x] 3.2 使用 openspec-sync-specs 同步主规格，完成差异检查并确认满足归档条件（归档作为完成后的流程动作）。

## 验证记录

- 先红后绿：旧实现的合成日摘要回归失败于 incomplete=false，前端两个新增场景失败；修复后通过。
- `mvn -o -pl self-analyst-app -am -Dtest=SummaryTimelineAssemblerTest,DesktopSummaryAssemblerTest,SummaryServiceTest,SummaryPromptServiceTest,SummaryFactFingerprintTest -Dsurefire.failIfNoSpecifiedTests=false test`：54 项 Java、264 项 Node 测试通过。Windows 属性参数实际使用单引号包裹；在正常用户临时目录运行以支持原子快照写入。
- 昨天 Wiki 的只读副本离线回放：9 个互不重叠的已完成时段，1 个质量失败小时，父状态 waiting_dependencies、incomplete=true，失败小时调用与重试信息准确；无模型调用。
- 合成页面浏览器验证：默认显示完整子摘要及范围，展开显示主题和质量失败／额度／11:42 重试时间；合成截图随代码提交至 docs/mockups/blocked-history-summary-preview.jpg，不包含用户真实窗口证据。
- OpenSpec change 严格验证通过；32 个主规格严格验证通过；git diff --check 通过。
- 中英文 README 已同步且语言正确。docs/ 下既有活动统计和运行数据指南保留，不需要改动；本次无新增历史文档。

- PR 前补跑完整 mvn -o test：全部模块 BUILD SUCCESS，264 项 Node 通过，日志保留在忽略目录 .tmp/blocked-summary-pr-full-test.log。
- PR 前 openspec validate --all --strict：全部 32 个主规格通过；本机未跟踪的既有 add-python-release-assistant 草稿校验失败，不属于本次提交。

- 同步最新 main（918d09c，beta.6 版本准备）后，完整 mvn -o test 再次 BUILD SUCCESS，264 项 Node 通过；日志位于 .tmp/blocked-summary-pr-synced-test.log。
