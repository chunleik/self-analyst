## ADDED Requirements

### Requirement: SPEC-WIKI-SRC-020 默认事实来源桶标识
HOUR、HALF_DAY 和 DAY 的事实构建 SHALL 默认查询本机投影桶
`watcher-window_{hostname}`、`watcher-afk_{hostname}` 和 `watcher-content_{hostname}`。
当对应导入或外部兼容桶使用 `aw-watcher-window_*`、`aw-watcher-afk_*` 或 `aw-watcher-content_*` 时，
系统 SHALL 仍能把它们识别为同一来源种类。Wiki MUST NOT 把 `wiki-*` 时间线投影桶当作事实来源。

#### Scenario: 本产品默认窗口桶
- **WHEN** Wiki 为已结束小时构造事实且本机存在 `watcher-window_{hostname}` 投影
- **THEN** 窗口应用耗时、切换次数和标题样本来自该桶

#### Scenario: 导入窗口桶仍可识别
- **WHEN** 仅存在 `aw-watcher-window_{hostname}` 投影而无 `watcher-window_*` 桶
- **THEN** 系统仍从该导入桶读取窗口事实，不因前缀不是 `watcher-` 而跳过

### Requirement: SPEC-WIKI-PROJ-010 Wiki 时间线投影桶
系统 MAY 把已完成摘要投影到本机时间线桶，权威仍是 `llm-wiki.db`。投影桶 ID SHALL 为
`wiki-hourly_{hostname}`、`wiki-halfday_{hostname}` 和 `wiki-daily_{hostname}`，client SHALL 为 `wiki`。
这些桶 MUST NOT 使用 `watcher-` 或 `aw-watcher-` 前缀。

#### Scenario: 小时摘要进入时间线桶
- **WHEN** 近七日存在 SUMMARIZED 的 HOUR 条目且时间线投影启用
- **THEN** 系统写入 `wiki-hourly_{hostname}`，事件含标题与摘要字段

#### Scenario: Wiki 桶不是采集器
- **WHEN** 创建或更新 Wiki 时间线投影桶
- **THEN** bucket client 为 `wiki`，ID 不以 `watcher-` 开头
