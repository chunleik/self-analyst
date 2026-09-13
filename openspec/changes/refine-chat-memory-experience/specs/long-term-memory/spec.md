## MODIFIED Requirements

### Requirement: SPEC-LTM-DEC-007、SPEC-LTM-EXTR-002 有界提炼输入
自动提炼输入 SHALL 限于当前会话 ID、标题、策略、最近 user/assistant turn 或有界消息窗口、相关消息 ID，以及用于候选比较的有界记忆摘要。摘要 SHALL 包含 active/pending 候选参照，并允许加入有界 rejected/disabled 抑制项；抑制项 MUST 标明仅用于防止重复推荐，不作为有效个人事实或进入聊天 Agent 记忆上下文。各状态条目 SHALL 共用固定输入预算，不得通过增加状态扩大为全库输入。输入 MUST NOT 包含配置密钥、凭据、完整历史会话库或原始采集事件流；凭据样式的聊天文本 SHALL 在调用提炼模型前被确定性拦截。

#### Scenario: 有界 prompt
- **WHEN** 当前消息和既有记忆内容很长
- **THEN** 提炼 prompt 只包含截断后的近期消息、记忆摘要、策略和来源 ID，并保持在固定预算内

#### Scenario: 凭据样式聊天文本
- **WHEN** 最近 user/assistant turn 包含 API key、client secret 或等价凭据
- **THEN** 系统不把该 turn 发送给提炼模型，也不创建候选

#### Scenario: 拒绝记录仅用于抑制
- **WHEN** 有界提炼摘要包含 rejected 或 disabled 记录
- **THEN** 该记录只参与候选重复抑制，不被当作已确认事实注入聊天上下文

### Requirement: SPEC-LTM-EXTR-004、005 自动与待确认条件
系统 SHALL 在分级入库前校验长期复用价值和来源。纯活动查询、单次观看视频、浏览文档、打开笔记以及仅由 assistant 活动总结推断的兴趣或项目状态 MUST NOT 新增 active 或 pending 记忆。合格候选 SHALL 有用户明确记忆意图或用户陈述支持的稳定偏好、长期目标、项目、可复用事实；缺少可核验来源或长期价值说明时 MUST 丢弃。
只有用户明确陈述、非敏感、非凭据、可长期复用、confidence 至少 8 且未重复的候选 MAY 自动 active。通过资格校验的推断、行为模式、敏感类别、confidence 低于 8 或 confirm_all 会话候选 MUST pending。系统 MUST NOT 仅根据模型给出的 approvalPolicy 绕过确定性来源校验和分级。

#### Scenario: 模型把敏感候选标为 auto
- **WHEN** 候选标记 sensitive 或属于敏感推断，但模型建议 auto
- **THEN** 系统不自动 active，候选进入 pending 或被拒绝

#### Scenario: 纯活动查询
- **WHEN** 用户只询问最近一小时做了什么，assistant 总结观看视频、浏览 GitHub 和 DeepSeek 文档的活动
- **THEN** 系统不因这些活动新增 active 或 pending 记忆，即使模型将它们标为高置信长期偏好

#### Scenario: 明确长期背景与活动查询混合
- **WHEN** 用户既询问近期活动，又明确陈述自己长期开发 SelfAnalyst
- **THEN** 仅长期项目陈述可以形成候选，按会话策略处理，不保存其它单次活动推断

#### Scenario: 来源缺失或伪造
- **WHEN** 候选的依据仅来自 assistant，或所引用的用户消息和原文片段不能在本次有界输入中核验
- **THEN** 服务端丢弃候选，不因高置信度或 auto 建议直接保存

### Requirement: SPEC-LTM-EXTR-009 预算与语言
提炼调用 SHALL 计入有效 LLM 用量预算；预算阻断时本次提炼 no-op。提炼 prompt、新生成候选的概括正文和依据说明 SHALL 跟随后端有效语言 zh 或 en，不以来源消息语言代替用户选择的语言。候选内容 MUST 保持用户原意，不得为了语言统一改变事实；引用的原始标题、路径、项目名和用户原文 SHALL 保留原样。语言设置 SHALL 按既有国际化生效时机影响后续生成，不批量翻译或覆写历史记忆和手动输入；用途、按钮和状态等界面文案 SHALL 使用当前有效语言。

#### Scenario: 英文有效语言
- **WHEN** 应用有效语言为 English，用户用中文陈述长期偏好
- **THEN** 提炼 prompt 使用英文指令，新候选和生成的依据说明使用英文并保留原意及原始引用

#### Scenario: 中文有效语言
- **WHEN** 应用有效语言为 zh，来源包含英文项目名和英文消息
- **THEN** 新候选概括与依据说明使用中文，项目名和引用原文保持不变

#### Scenario: 语言变更与旧数据
- **WHEN** 用户变更语言并按既有流程使其生效
- **THEN** 后续候选按新的有效语言生成，界面用途和操作文案更新；历史记忆正文与手动输入保持原样

#### Scenario: 提炼预算不足
- **WHEN** 提炼预算被阻断
- **THEN** 系统不生成候选，不为翻译另行调用模型，聊天结果不受影响

### Requirement: SPEC-LTM-FE-001..008 桌面记忆管理体验
桌面会话页 SHALL 通过按需展开的记忆区域提供当前会话策略、pending 数量、相关记忆摘要、手动添加、编辑、批准、拒绝、停用、重新启用和删除操作；这些操作 MUST 调用后端而非只改前端状态。待确认候选 SHALL 简短说明“批准后用于后续会话”，active 记忆 SHALL 显示已生效状态，避免把所有记忆都描述为待批准。每条候选 SHALL 提供可展开的来源或 evidence，详细时间和窗口标题默认折叠，来源缺失时显示本地化提示而非编造依据。空、加载、保存失败状态 SHALL 有明确本地化文案，失败 MUST NOT 清空编辑文本。记忆管理 MUST 位于会话界面而不是 config.toml 编辑器。

#### Scenario: 候选批准失败
- **WHEN** 用户批准候选但后端保存失败
- **THEN** 页面显示错误并保留候选与编辑内容，不伪造 active 状态

#### Scenario: 查看用途和依据
- **WHEN** 用户打开一条待确认候选
- **THEN** 页面显示候选短句、批准后的用途及操作按钮，用户展开“查看依据”后才显示详细来源

## ADDED Requirements

### Requirement: SPEC-LTM-EXTR-010 同义候选与拒绝抑制
自动提炼 SHALL 在有界上下文内识别与已有记忆表达同一事实的候选，包含中英文同义表述；已有 active 或 pending 项时 SHALL 复用已有项或合并可核验来源，不创建重复项、不覆盖已批准内容。已有 rejected 或 disabled 项时 MUST NOT 借同义表述自动重新推荐或激活；物理删除后不得继续参与去重。无法可靠确认同义关系时 MUST NOT 修改已有记忆。既有规范化正文去重 SHALL 继续生效。

#### Scenario: 重复提炼同一项目
- **WHEN** 已有“用户长期开发 SelfAnalyst”的 pending 记忆，随后产生表达同一事实的英文候选
- **THEN** 系统不新增第二条候选，原记忆状态不变

#### Scenario: 被拒绝的事实重新出现
- **WHEN** 有界去重上下文内存在被拒绝的事实，模型再次推荐该事实的同义表述
- **THEN** 系统不创建新的待确认项，也不自动恢复原记忆

#### Scenario: 仅相关而非相同
- **WHEN** 用户提出与既有项目相关但含义不同的新长期目标，无法确认它与旧记忆同义
- **THEN** 不覆写或合并旧记忆，新候选独立接受资格校验
