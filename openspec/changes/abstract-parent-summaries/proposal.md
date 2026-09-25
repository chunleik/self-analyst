## Why

半天和日摘要仍直接重读原始标题，结果是把小时里已经归纳过的活动再列一遍。周摘要虽然读子条目，但子片段没有时长，长尾仍会被展开成清单。

## What Changes

- **行为修改**：HALF_DAY 只聚合已完成的 HOUR，DAY 只聚合已完成的 HALF_DAY。预期子块未全部 SUMMARIZED 或 SKIPPED 时，父级保持 PENDING。HOUR 仍读原始标题事实。
- **行为修改**：子片段进入上层时带上该子周期的有效秒数，上层按秒数排序。上层最终最多 3 个主题，其余写成“其余活动不再展开”，不逐条点名。
- 已有摘要不自动重算。

## Capabilities

### New Capabilities

（无）

### Modified Capabilities

- `llm-wiki`：修改层级输入依赖，并新增上层抽象输出要求。

## Impact

- `WikiWorker` 的父子层级，`WikiFactBuilder` 的子事实秒数，`WikiSummaryFocus` 的上层限量，`WikiTopicProtocol` 的上层提示词。
