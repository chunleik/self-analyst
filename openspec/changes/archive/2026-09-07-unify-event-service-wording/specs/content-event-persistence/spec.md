## MODIFIED Requirements

### Requirement: SPEC-CTP-002 标题事实派生边界
Wiki 从内容事件投影构造事实时 SHALL 只读取窗口标题、上下文标题、应用和上下文种类，MUST NOT 直接查询逐 heartbeat 原始事件层，也 MUST NOT 读取 `text_content`。更高层级 Wiki 聚合 MAY 消费子级 metrics、summary 和 primaryTask，并 MAY 持久化由标题事实及这些既有派生结果生成的摘要、主要任务和任务片段。

#### Scenario: 旧正文行被消费者忽略
- **WHEN** 内容 bucket 投影中存在一条带系统标题和旧 `text_content` 的历史行
- **THEN** Wiki 使用系统标题作为回退标题事实，且 facts 和 prompt 不包含旧正文

#### Scenario: Wiki 不查询逐 heartbeat 原始层
- **WHEN** Wiki 为一个时间段构造内容事实
- **THEN** 系统从内容事件投影读取标题事实，不通过桌面原始事件 API读取逐条 heartbeat

### Requirement: SPEC-CTP-040 Wiki 与 Agent 的内容事实边界
Wiki 从原始内容事件构造事实时 SHALL 只读取标题事实，并在缺少 `context_title` 时
使用系统 `title`；父级 Wiki 聚合 MAY 使用子级 metrics、summary 和 primaryTask。Wiki prompt MUST NOT
包含 `text_content` 或屏幕正文片段。Agent 的通用事件查询不执行第二次内容字段投影，
因此其内容安全保证 SHALL 来自对应 bucket 成功迁移和统一写入策略，而不是工具层字段过滤。

#### Scenario: 标题事实进入 Wiki prompt
- **WHEN** Wiki 处理同时含上下文标题和仅含系统标题的内容事件
- **THEN** prompt 使用相应标题样本，不包含旧正文

#### Scenario: Agent 查询健康内容 bucket
- **WHEN** Agent 通过通用事件查询工具查询属于迁移选择范围、已成功迁移并受写策略保护的内容 bucket
- **THEN** 返回的持久化 data 只包含内容事件允许字段

### Requirement: SPEC-CTP-050 内容策略先于永久原始提交
所有内容 heartbeat、events 和 import MUST 在写入永久原始事件层前执行内容事件 v2 策略。通过策略的每个原始内容事件 SHALL 永久保存其 v2 标题字段且不受后续 heartbeat 投影合并影响；策略失败的 payload MUST NOT 进入原始事件层。

#### Scenario: 内容原始 heartbeat 永久保留
- **WHEN** 多个合法内容 heartbeat 通过策略后被事件投影合并
- **THEN** 原始层仍分别保存每个 heartbeat，且每条 data 只包含内容 v2 允许字段

#### Scenario: 正文键在原始写入前拒绝
- **WHEN** 内容事件包含 `text_content`、`uia_text`、`raw_tree` 或其它禁止字段
- **THEN** 系统返回 `422`，永久原始层和事件投影均不包含该事件或禁止值
