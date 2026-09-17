## RENAMED Requirements

- FROM: `### Requirement: SPEC-CTP-050 内容策略先于永久原始提交`
- TO: `### Requirement: SPEC-CTP-050 内容策略先于事件提交`

## MODIFIED Requirements

### Requirement: SPEC-CTP-002 标题事实派生边界
Wiki 从合并内容事件构造事实时 SHALL 只读取窗口标题、上下文标题、应用和上下文种类，MUST NOT 查询旧逐 heartbeat 原始备份，也 MUST NOT 读取 `text_content`。更高层级 Wiki 聚合 MAY 消费子级 metrics、summary 和 primaryTask，并 MAY 持久化由标题事实及这些既有派生结果生成的摘要、主要任务和任务片段。

#### Scenario: 旧正文行被消费者忽略
- **WHEN** 内容 bucket 中存在一条带系统标题和旧 `text_content` 的历史行
- **THEN** Wiki 使用系统标题作为回退标题事实，且 facts 和 prompt 不包含旧正文

#### Scenario: Wiki 不查询逐 heartbeat 原始层
- **WHEN** Wiki 为一个时间段构造内容事实
- **THEN** 系统读取合并内容事件，不扫描逐 heartbeat 备份

### Requirement: SPEC-CTP-050 内容策略先于事件提交
所有内容 heartbeat、events 和 import MUST 在事件写入前执行内容事件 v2 策略。合法标题 heartbeat SHALL 按合并规则保存活动区间，不再永久保留逐心跳副本；策略失败的 payload MUST NOT 进入事件库、回执或日志。

#### Scenario: 内容原始 heartbeat 永久保留
- **WHEN** 多个合法且连续等价的内容 heartbeat 通过策略
- **THEN** 更新同一活动区间，data 仅包含内容 v2 允许字段

#### Scenario: 正文键在原始写入前拒绝
- **WHEN** 内容事件包含 `text_content`、`uia_text`、`raw_tree` 或其它禁止字段
- **THEN** 系统返回 `422`，事件库、回执和日志均不包含该禁止值
