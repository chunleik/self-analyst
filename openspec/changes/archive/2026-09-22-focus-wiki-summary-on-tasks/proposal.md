## Why

真实摘要反复复述 AFK 覆盖和活动时长，挤占任务、项目与技术主题的叙述空间。用户已确认这些统计信息保留为内部判断及结构化指标，不再出现在 Wiki 摘要文案中。

## What Changes

- 调整 Wiki 提示词，明确区分内部指标和对用户的任务叙述，统计不再作为自然语言输出要求。
- 对 summary、primaryTask、任务标题/描述/证据增加统计话术校验；违规响应进入现有失败重试，不通过删除句子拼凑摘要。
- 覆盖不足时约束任务置信度与成果断言；保留 metrics、sourceCoverage、统计口径和采样预算。
- 同步现行 Wiki 规格中“AFK 缺失必须在生成文案体现”的要求，复验冻结三日快照的真实模型输出。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

- `llm-wiki`: 任务导向的摘要文案、内部统计边界及输出校验。

## Impact

影响 WikiSummarizer、对应回归测试和 Wiki 规格/说明。不更改桌面独立统计字段、原始活动数据、采样算法或已有已完成摘要；本次生成文案政策作用于新 Wiki 摘要及其下游复用。
