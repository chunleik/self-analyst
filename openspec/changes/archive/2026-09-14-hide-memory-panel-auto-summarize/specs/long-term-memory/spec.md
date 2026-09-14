## MODIFIED Requirements

### Requirement: SPEC-LTM-DEC-002、004、SPEC-LTM-GOAL-002..004 分级入库与会话策略
会话 memoryPolicy SHALL 接受 `smart/confirm_all/off`，缺失时等价 smart，confirm_all SHALL 兼容为 smart 自动筛选。
系统 SHALL 自动保存有长期价值、证据支持、非敏感、非凭据且 confidence 至少 8 的候选为 active，其余丢弃，MUST NOT 为自动候选新增 pending。
off SHALL 跳过自动提炼，但允许显式会话纠错和 API 管理。

#### Scenario: smart 自动保存低风险偏好
- **WHEN** 用户明确陈述长期偏好，候选非敏感、置信度至少 8 且未重复
- **THEN** 候选保存为 active、source 为 chat_auto

#### Scenario: smart 敏感或推断候选
- **WHEN** 候选来自行为推断、标记敏感或置信度低于 8
- **THEN** 候选经自动筛选，敏感、低可信及证据不足者丢弃；有证据支持且满足长期价值与安全要求的归纳保存为 active

#### Scenario: confirm_all
- **WHEN** 会话策略为 confirm_all 且模型返回有效 auto 候选
- **THEN** 按 smart 自动筛选，合格者 active，其余丢弃，不进入人工审批队列

#### Scenario: off
- **WHEN** 会话策略为 off
- **THEN** 系统不调用自动提炼模型，但手动记忆管理仍可用

#### Scenario: 默认后台总结
- **WHEN** 新会话或未设置策略的旧会话首次成功保存 assistant 回复
- **THEN** 系统按 smart 异步总结长期记忆，无需用户打开界面或点击确认才能触发；显式 off 保留，confirm_all 按 smart 兼容处理

### Requirement: SPEC-LTM-DEC-006、SPEC-LTM-GOAL-005 用户可管理性
系统 SHALL 保留查看、搜索、手动添加、编辑、批准、拒绝、停用、重新启用和删除长期记忆的 API。
自动或候选记忆 SHALL 保留 evidence 或来源说明及状态、敏感标记；桌面会话页不提供这些管理操作。

#### Scenario: 批准 pending
- **WHEN** 用户批准一条 pending 记忆
- **THEN** 后端更新为 active，下一轮 Agent 可以使用

#### Scenario: 删除记忆
- **WHEN** 用户删除一条记忆
- **THEN** 该项从 memory.json 物理移除，不再进入上下文或去重集合

### Requirement: SPEC-LTM-EXTR-007 用户记住与忘记指令
用户明确要求记住的内容 SHALL 在非 off 会话按自动筛选规则处理；敏感或低可信内容丢弃。
结构化 disable action SHALL 按记忆 ID 停用匹配记忆。用户明确更正或忘记已有记忆 SHALL 通过会话处理，包括 off 会话；指代不清 MUST 先追问。只有持久化成功后才能告知已更新，失败 MUST 如实说明。

#### Scenario: 非 off 会话的 remember 候选
- **WHEN** 用户明确要求记住非敏感长期信息，提炼模型返回合法候选
- **THEN** 系统按 smart 或 confirm_all 策略保存候选

#### Scenario: 结构化 disable action
- **WHEN** 非 off 会话的提炼响应包含指向现有记忆 ID 的 disable action
- **THEN** 系统把该记忆状态更新为 disabled

#### Scenario: off 会话中的自然语言忘记
- **WHEN** 会话策略为 off 且用户只在聊天中说“忘记”
- **THEN** 自动提炼不运行；明确指向的已有记忆仍通过显式操作停用，指代不明确时先追问，不擅自更改其它记忆

### Requirement: SPEC-LTM-API-002、006 手动创建记忆
`POST /desktop/memory` SHALL 创建 ui_manual 记忆；`POST /desktop/chat/sessions/{id}/memory` SHALL
创建 chat_manual 记忆并自动填充 sourceSessionId。服务端 SHALL 生成 ID、时间戳和默认 confidence=10；
敏感或需确认内容 MAY 保存为 pending。

#### Scenario: 从会话手动添加
- **WHEN** 客户端通过会话记忆 API 提交合法短句
- **THEN** 服务端创建 source=chat_manual、关联当前 session 的记忆

### Requirement: SPEC-LTM-FE-001..008 桌面记忆管理体验
桌面会话页 SHALL 隐藏整个长期记忆管理面板，包括策略选择、pending 数量、记忆摘要、手动添加和候选操作。
页面 MUST NOT 留下该面板的空白占位或可聚焦的隐藏控件，MUST NOT 为该面板单独请求记忆列表。
隐藏界面 MUST NOT 停止后台自动总结；历史 pending SHALL 通过后台重新评估处理，不能仅因隐藏界面而全部激活。

#### Scenario: 打开或切换会话
- **WHEN** 用户打开、切换或刷新含 pending 记忆的会话
- **THEN** 页面不显示长期记忆管理界面、不保留其布局占位，也不请求该面板的记忆列表

#### Scenario: 后台总结继续运行
- **WHEN** smart 会话产生成功的 assistant 回复且 LLM 与预算可用
- **THEN** 系统继续异步提炼，自动保存合格记忆或丢弃不合格候选，不要求人工审批

#### Scenario: 候选批准失败
- **WHEN** 客户端通过保留的 API 批准候选但后端保存失败
- **THEN** API 返回错误并保留候选原状态，不伪造 active 状态；桌面会话页不显示审批界面

### Requirement: SPEC-LTM-SEC-003..005 用户控制与提炼隐私
rejected、disabled 和 pending 记忆 MUST NOT 进入 Agent 上下文；删除项 MUST 从 memory.json 移除。
API SHALL 允许用户停用或删除错误记忆。提炼输入 MUST NOT 包含配置密钥，并 SHALL 遵守会话消息和
摘要的敏感数据边界。

#### Scenario: 删除错误记忆
- **WHEN** 用户删除一条错误或敏感记忆
- **THEN** 该内容不再出现在存储、下一轮 Agent 上下文或自动去重依据中

### Requirement: SPEC-LTM-EXTR-004、005 自动与待确认条件
系统 SHALL 自动筛选候选，仅将有长期价值、证据支持、非敏感、非凭据、confidence 至少 8 且未重复的候选保存为 active。
敏感推断、低可信、一次性内容 SHALL 丢弃，不进入 pending。系统 MUST NOT 仅根据模型 approvalPolicy 绕过确定性过滤。

#### Scenario: 模型把敏感候选标为 auto
- **WHEN** 候选标记 sensitive 或属于敏感推断，但模型建议 auto
- **THEN** 系统丢弃该候选，不保存为 active 或 pending

### Requirement: SPEC-LTM-NON-001..006 功能边界与事件来源
长期记忆 SHALL 保持本地文件能力，不提供云同步、账号体系、跨设备合并、向量召回或 embedding 检索。
系统 MUST NOT 自动把原始采集事件或全部聊天原文复制为长期记忆；legacy goals、patterns、
logs 不要求迁移成 MemoryItem，但 SHALL 继续兼容读取和上下文展示。

#### Scenario: 原始采集事件
- **WHEN** 系统存在新的窗口、AFK 或内容事件
- **THEN** 这些原始事件不会自动写入长期记忆；行为推断只能经有界候选和自动筛选策略处理

## ADDED Requirements

### Requirement: SPEC-LTM-MIG-001 历史待确认记忆自动重评
系统 SHALL 在启动后后台分批评估历史 pending，无需用户打开面板或发起聊天。具有长期价值、证据支持并满足自动筛选条件的记录 SHALL 保留原 ID 激活，其余 SHALL 从记忆存储删除。
凭据 MUST 在模型调用前过滤。重评 SHALL 遵守预算及有界输入；技术失败 MUST 保留未完成记录并自动退避重试，不能当作不合格删除。
系统 MUST NOT 重评 active、disabled、rejected 或覆盖并发更正、重新创建并发删除的记录。重启和重试 SHALL 幂等。

#### Scenario: 历史记录处理完成
- **WHEN** 历史 pending 中分别存在合格长期信息和一次性信息
- **THEN** 合格项保留原 ID 转 active，一次性项被删除，两者均无需人工审批

#### Scenario: 重评技术失败
- **WHEN** 模型不可用、预算阻断、输出无效或保存失败
- **THEN** 未完成项保持隔离且不丢失，后台稍后重试，聊天不受影响

#### Scenario: 用户并发更正
- **WHEN** 重评期间用户已修改、停用或删除目标记忆
- **THEN** 旧评估结果不覆盖用户操作，也不恢复该记忆

### Requirement: SPEC-LTM-CHAT-001 会话内澄清记忆
用户明确质疑已记住的信息时，助手 SHALL 提示可在当前会话说明正确情况或要求忘记，不要求进入管理界面。
指代不明确时 SHALL 先追问；用户已提供明确目标和更正时 SHALL 直接处理，不重复要求说明。
更正 SHALL 原子替换目标内容或停用旧项并保存合格的新内容，避免新旧事实同时生效；忘记 SHALL 停用目标。
用户未质疑时 MUST NOT 每轮主动插入澄清提示。仅在持久化成功后 SHALL 告知完成，失败时 SHALL 如实反馈。

#### Scenario: 用户指出记错但未说明正确情况
- **WHEN** 用户说某条记忆不准确且未给出更正
- **THEN** 助手邀请用户在会话中澄清或要求忘记，不擅自写入猜测内容

#### Scenario: 明确更正或忘记
- **WHEN** 用户明确给出目标及正确内容，或要求忘记明确目标
- **THEN** 系统处理对应记忆，保存成功后确认完成，下一轮上下文不再使用旧内容

#### Scenario: 指代不明或保存失败
- **WHEN** 用户说“这不对”但目标不明，或者已明确的修改保存失败
- **THEN** 前者先追问且不修改记录，后者说明未能保存，不声称已更新
