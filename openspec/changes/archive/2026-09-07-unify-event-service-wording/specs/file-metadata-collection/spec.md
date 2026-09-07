## MODIFIED Requirements

### Requirement: SPEC-FILE-063 FileTools 当前监控根限制
FileTools 查询 SHALL 只暴露当前启用监控根内的元数据；禁用文件采集或移除监控根后，FileTools
MUST 隐藏对应历史名称、路径和时间。本保证只覆盖 FileTools，不扩展到通用事件查询工具
或原始桌面历史接口。

#### Scenario: SPEC-FILE-TST-011 禁用或移除监控根
- **WHEN** 文件采集被禁用，或某个监控根从当前设置中移除
- **THEN** FileTools 不再返回该范围的历史文件元数据

### Requirement: SPEC-FILE-090 文件元数据原始事件永久保留
通过文件过滤与字段白名单的每个已发送文件 heartbeat SHALL 在事件投影前作为合规原始事件永久保存。原始文件事件 MUST 继续只包含允许的路径与文件系统元数据，MUST NOT 读取或保存普通文件正文、内容哈希、摘要、主题或 embedding。去抖与 heartbeat 节流发生在采集器生成原始事件之前，不得通过删除已提交原始事件实现节流。

#### Scenario: 文件 heartbeat 投影合并
- **WHEN** 两个已发送文件 heartbeat 被事件时间线合并
- **THEN** 两个原始元数据事件均保持存在且只包含文件 heartbeat 白名单字段

#### Scenario: 普通文件正文保持隔离
- **WHEN** 被监控文件正文包含秘密标记并产生元数据原始事件
- **THEN** 原始事件、manifest、日志和投影均不包含正文秘密标记
