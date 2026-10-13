## Context

动机见 proposal.md。只读调查核对了 2026-10-09 04:00 至 2026-10-10 04:00（Asia/Shanghai）的事件与 Wiki：2,406 条窗口记录、228 个不同窗口标题；内容标题记录 6,347 条。19:00–20:00 小时的 22 条候选事实全部入选，失败状态为 quality，错误码为 WIKI_EVIDENCE_UNSUPPORTED_CLAIM:taskSegments.summary，累计 7 次调用、31,505 tokens。上午 HALF_DAY 已完成，后半天和 DAY 等待依赖，DAY 尚未形成模型请求。

失败响应没有持久化，无法证明具体被拒句子或校验误报；不将猜测作为放宽证据规则的依据。主要窗口列表仅取排名第一应用的标题，因此截图中两个标题不代表全日输入。桌面快照 source=local、insight 为空、incomplete=false；父条目自身无 generationProgress，导致真实子级失败未显示。

## Goals / Non-Goals

**Goals:** 恢复可用历史主题的可读性，让部分范围和阻塞原因可验证。

**Non-Goals:** 不把部分结果写为 SUMMARIZED，不删除失败小时，不改重试准入／质量校验，不重新调用线上模型或直接修用户数据。原始标题和完整模型请求不写入仓库。

## Decisions

- DAY/HALF_DAY 独立使用只读依赖遍历，以 WikiPeriodFactory 产生精确子周期，校验时区、边界及当前统计版本。DAY → HALF_DAY → HOUR 最深两层；同一层范围批量读取，避免每小时单独查询。完成的父节点阻止展开其后代，保证无重复覆盖。
- 新增 partialSummaries（保存子周期范围、原摘要和主题）及 summaryStatus（父状态与阻塞叶节点）。只有已保存 SUMMARIZED 且非空文案参与展示。SKIPPED 为依赖终态但不伪造摘要。
- 仅对部分结果赋 source=wiki-partial，所有未完整父摘要赋 incomplete=true。保持全时段本地指标，不叠加子级指标。由前端在默认折叠状态以“部分摘要”提示和带范围的子周期卡片展示完整原文，详情保留主题明细与失败诊断，避免拼成未经生成的全天主题。
- 复用 WikiGenerationProgress 的安全字段白名单，兼容旧条目只有 lastError/nextRetryAt 的情况：只允许受限错误码，不输出任意异常原文。下级消耗在对应下级行显示。
- 完整 Wiki 优先逻辑不变；页面刷新可直接升级到完整结果。现有周／月拼装保持原路径。

## Risks / Trade-offs

- 部分小时摘要较长 → 完整原文按时段默认展示并自然换行，详细主题在展开区阅读，默认清晰标记部分结果，不截断冒充概览。
- Wiki 查询失败 → 保留本地统计并标记不可用，不能误报无记录。
- 该小时仍可能质量失败 → 明确展示失败和退避时间，保留现有重试；修复不声称已恢复完整日摘要。

## Migration Plan

无数据库迁移；随应用升级生效，下一次快照重组使用新字段。旧客户端忽略新增字段；旧快照由常规刷新替换。回滚仅回退代码。
