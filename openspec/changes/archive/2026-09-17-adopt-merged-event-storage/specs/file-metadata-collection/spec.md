## RENAMED Requirements

- FROM: `### Requirement: SPEC-FILE-090 文件元数据原始事件永久保留`
- TO: `### Requirement: SPEC-FILE-090 文件元数据事件合并存储`
- FROM: `### Requirement: SPEC-FILE-091 当前文件状态与永久历史分离`
- TO: `### Requirement: SPEC-FILE-091 当前文件状态与活动历史分离`

## MODIFIED Requirements

### Requirement: SPEC-FILE-090 文件元数据事件合并存储
通过文件过滤与字段白名单的文件 heartbeat SHALL 使用合并事件存储。文件事件 MUST 继续只包含允许的路径与文件系统元数据，MUST NOT 读取或保存普通文件正文、内容哈希、摘要、主题或 embedding。去抖与 heartbeat 节流 SHALL 继续在采集器侧执行，不通过删除已保存活动区间实现节流。

#### Scenario: 文件 heartbeat 投影合并
- **WHEN** 两个连续等价文件 heartbeat 满足合并条件
- **THEN** 更新同一元数据活动区间，字段遵守白名单且不新增永久原始副本

#### Scenario: 普通文件正文保持隔离
- **WHEN** 被监控文件正文包含秘密标记并产生元数据事件
- **THEN** 事件库、回执和日志均不包含正文秘密标记

### Requirement: SPEC-FILE-091 当前文件状态与活动历史分离
文件当前状态存储与 FileTools SHALL 继续按当前启用监控根限制暴露记录；禁用文件采集、移除监控根或把文件标记为 `DELETED` MUST NOT 自动删除已保存的文件活动区间。受保护的桌面历史查询 SHALL 读取合并事件，不提供逐心跳历史恢复保证，不扫描迁移备份。

#### Scenario: 移除监控根
- **WHEN** 用户从当前配置移除一个监控根
- **THEN** FileTools 不再返回该根的历史元数据，既有合并活动区间保持不变
