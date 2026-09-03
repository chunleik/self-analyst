## ADDED Requirements

### Requirement: SPEC-FILE-090 文件元数据原始事件永久保留
通过文件过滤与字段白名单的每个已发送文件 heartbeat SHALL 在 ActivityWatch 投影前作为合规原始事件永久保存。原始文件事件 MUST 继续只包含允许的路径与文件系统元数据，MUST NOT 读取或保存普通文件正文、内容哈希、摘要、主题或 embedding。去抖与 heartbeat 节流发生在采集器生成原始事件之前，不得通过删除已提交原始事件实现节流。

#### Scenario: 文件 heartbeat 投影合并
- **WHEN** 两个已发送文件 heartbeat 被 ActivityWatch 时间线合并
- **THEN** 两个原始元数据事件均保持存在且只包含文件 heartbeat 白名单字段

#### Scenario: 普通文件正文保持隔离
- **WHEN** 被监控文件正文包含秘密标记并产生元数据原始事件
- **THEN** 原始事件、manifest、日志和投影均不包含正文秘密标记

### Requirement: SPEC-FILE-091 当前文件状态与永久历史分离
文件当前状态存储与 FileTools SHALL 继续按当前启用监控根限制暴露记录；禁用文件采集、移除监控根或把文件标记为 `DELETED` MUST NOT 删除已经提交的原始文件元数据事件。原始历史只能通过受保护的桌面原始事件 API 按明确 bucket 和时间范围查询。

#### Scenario: 移除监控根
- **WHEN** 用户从当前配置移除一个监控根
- **THEN** FileTools 不再返回该根的历史元数据，但永久原始事件计数与内容保持不变
