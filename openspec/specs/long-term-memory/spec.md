# 长期记忆规格

## Purpose

定义会话消息提炼为长期记忆、分级确认、用户管理、动态 Agent 上下文、持久化和隐私过滤行为，确保记忆提炼失败或模型不可用不会阻断聊天主流程。

## Requirements

### Requirement: SPEC-LTM-REL-001、002、SPEC-LTM-DEC-001 记忆权威与旧数据兼容
长期记忆、legacy goals、patterns 和 logs SHALL 以 `{memoryDir}/memory.json` 为唯一事实来源，并只通过
受控 MemoryStore 读写。缺少 `memories` 字段的旧文件 SHALL 视为空列表，保存新记忆时 MUST 保留旧字段
并继续使用格式化 JSON 与 ISO 时间。系统 MUST NOT 为第一版另建 SQLite 或向量库作为记忆权威。

#### Scenario: 读取旧 memory.json
- **WHEN** memory.json 只包含 legacy goals、patterns 或 logs，没有 memories
- **THEN** 系统成功加载旧字段并把 memories 初始化为空列表

#### Scenario: 保存扩展 profile
- **WHEN** 用户新增长期记忆并保存
- **THEN** memory.json 同时保留旧字段和新 memories，并以可读 JSON 原子替换

### Requirement: SPEC-LTM-REL-003..005、SPEC-LTM-DEC-003 长期记忆与聊天解耦
会话 SHALL 保存 memoryPolicy，assistant 首次从 pending 转为 sent 后 MAY 异步触发记忆提炼。提炼、解析、
去重、预算或保存失败 MUST NOT 改变已成功的聊天响应、消息状态或桌面摘要。提炼模型调用 SHALL 计入
LLM 预算；预算阻断或 LLM 不可用时本次提炼 SHALL no-op。

#### Scenario: assistant sent 后异步提炼
- **WHEN** assistant 消息首次成功更新为 sent 且会话策略允许提炼
- **THEN** 聊天响应无需等待提炼完成，后端异步处理记忆候选

#### Scenario: 提炼失败
- **WHEN** 模型不可用、预算阻断、JSON 无效或保存失败
- **THEN** 聊天 turn 保持成功，长期记忆不产生部分写入

### Requirement: SPEC-LTM-DEC-002、004、SPEC-LTM-GOAL-002..004 分级入库与会话策略
会话 memoryPolicy SHALL 为 `smart/confirm_all/off`，缺失时等价 `smart`。`smart` MAY 自动激活用户明确
陈述、非敏感、非凭据、置信度至少 8 且可长期复用的候选；推断、行为模式、敏感、低置信度候选 SHALL
进入 pending。`confirm_all` SHALL 让所有有效候选进入 pending；`off` SHALL 跳过自动提炼但仍允许用户
通过 UI 或 API 手动添加记忆。

#### Scenario: smart 自动保存低风险偏好
- **WHEN** 用户明确陈述长期偏好，候选非敏感、置信度至少 8 且未重复
- **THEN** 候选保存为 active、source 为 chat_auto

#### Scenario: smart 敏感或推断候选
- **WHEN** 候选来自行为推断、标记敏感或置信度低于 8
- **THEN** 候选保存为 pending，等待用户确认

#### Scenario: confirm_all
- **WHEN** 会话策略为 confirm_all 且模型返回有效 auto 候选
- **THEN** 候选仍保存为 pending，不自动进入上下文

#### Scenario: off
- **WHEN** 会话策略为 off
- **THEN** 系统不调用自动提炼模型，但手动记忆管理仍可用

### Requirement: SPEC-LTM-DEC-005、008、SPEC-LTM-PROMPT-001..005 动态 active 记忆上下文
每次 Agent reasoning SHALL 从当前 MemoryStore profile 构造最新记忆摘要，只包含 status=active 的
MemoryItem 和仍有效的 legacy goals、patterns、logs。pending、rejected、disabled MUST NOT 进入模型
上下文。摘要 SHALL 按类别分组并限制长度，优先目标、偏好和长期项目，再保留高置信事实与模式。
记忆变更 SHALL 在下一轮聊天生效，不要求重启；可变长期记忆 MUST NOT 固化进构造期 system prompt。

#### Scenario: active 与 pending 同时存在
- **WHEN** profile 同时含 active、pending、disabled 和 rejected 记忆
- **THEN** 下一轮模型上下文只包含 active 与有效 legacy 内容

#### Scenario: 运行时修改记忆
- **WHEN** 用户批准、编辑、停用或删除记忆后开始下一轮聊天
- **THEN** 新一轮上下文反映最新 profile，无需重启 Agent

### Requirement: SPEC-LTM-DEC-006、SPEC-LTM-GOAL-005 用户可管理性
用户 SHALL 能查看、搜索、手动添加、编辑、批准、拒绝、停用、重新启用和删除长期记忆。自动或候选
记忆 SHALL 展示 evidence 或来源说明，使用户能理解为何被记住。失败状态 MUST 保留用户正在编辑的文本，
敏感或待确认候选 MUST 清晰显示。

#### Scenario: 批准 pending
- **WHEN** 用户批准一条 pending 记忆
- **THEN** 后端更新为 active，下一轮 Agent 可以使用

#### Scenario: 删除记忆
- **WHEN** 用户删除一条记忆
- **THEN** 该项从 memory.json 物理移除，不再进入上下文或去重集合

### Requirement: SPEC-LTM-DEC-007、SPEC-LTM-EXTR-002 有界提炼输入
自动提炼输入 SHALL 限于当前会话 ID、标题、策略、最近 user/assistant turn 或有界消息窗口、相关消息 ID，
以及 active/pending 记忆的有界摘要。输入 MUST NOT 包含配置密钥、凭据、完整历史会话库或原始采集
事件流；凭据样式的聊天文本 SHALL 在调用提炼模型前被确定性拦截。

#### Scenario: 有界 prompt
- **WHEN** 当前消息和既有记忆内容很长
- **THEN** 提炼 prompt 只包含截断后的近期消息、记忆摘要、策略和来源 ID，并保持在固定预算内

#### Scenario: 凭据样式聊天文本
- **WHEN** 最近 user/assistant turn 包含 API key、client secret 或等价凭据
- **THEN** 系统不把该 turn 发送给提炼模型，也不创建候选

### Requirement: SPEC-LTM-MDL-001..007 MemoryItem 模型与去重
MemoryItem SHALL 提供服务端 ID、type、短句 content、可选 evidence、1..10 confidence、status、sensitive、
approvalPolicy、source、可选 sourceSessionId、sourceMessageIds 与创建/更新时间。type SHALL 为
`goal/preference/project/pattern/fact/note`；status SHALL 为 `active/pending/disabled/rejected`；source
SHALL 为 `chat_auto/chat_manual/ui_manual/legacy`。content MUST 非空并去除首尾空白，confidence SHALL
夹取到 1..10。规范化 content 已存在 active 或 pending 项时 MUST 不创建重复项；rejected 可用于抑制
重复推荐但不得进入上下文，物理删除后不得继续参与去重。

#### Scenario: 缺失 memories 字段
- **WHEN** 旧 profile 反序列化时没有 memories
- **THEN** 模型暴露可修改空列表，而不是 null

#### Scenario: 重复候选
- **WHEN** 同一规范化 content 已存在 active 或 pending 记忆
- **THEN** 新候选被忽略或合并来源，不创建重复记忆

#### Scenario: 非 active 状态
- **WHEN** 记忆状态为 pending、disabled 或 rejected
- **THEN** 该项不进入 Agent 记忆摘要

### Requirement: SPEC-LTM-MDL-010、011 会话记忆策略模型
Session 与 SessionMeta SHALL 提供 memoryPolicy，允许值为 `smart/confirm_all/off`，缺失时按 smart 处理。
前端 MAY 在不加载完整消息的情况下显示和修改当前策略。

#### Scenario: 非法策略
- **WHEN** 客户端提交不在允许集合中的 memoryPolicy
- **THEN** 后端返回 400，原会话策略保持不变

### Requirement: SPEC-LTM-EXTR-001、003 提炼触发与结构化输出
assistant 首次由 pending 转为 sent 后，系统 SHALL 在会话策略非 off 时调度一次提炼。模型响应 SHALL
解析为结构化候选或 disable action；无效 JSON、缺失字段或单个非法候选 MUST 被丢弃，且不得使聊天接口
失败。一个非法候选 MUST NOT 阻止同一响应中的其它可验证候选。

#### Scenario: 同一 assistant 重复更新
- **WHEN** 同一 assistant 已经为 sent，又收到重复 sent 更新
- **THEN** 系统不重复触发相同来源消息的提炼

#### Scenario: 混合合法和非法候选
- **WHEN** 模型响应同时包含凭据候选和合法偏好候选
- **THEN** 系统丢弃凭据候选并继续处理合法候选

### Requirement: SPEC-LTM-EXTR-004、005 自动与待确认条件
只有用户明确陈述、非敏感、非凭据、可长期复用、confidence 至少 8 且未重复的候选 MAY 自动 active。
模型推断、行为模式、敏感类别、采集事件行为归纳、confidence 低于 8 或 confirm_all 会话候选
MUST pending。系统 MUST NOT 仅根据模型给出的 approvalPolicy 绕过确定性分级。

#### Scenario: 模型把敏感候选标为 auto
- **WHEN** 候选标记 sensitive 或属于敏感推断，但模型建议 auto
- **THEN** 系统不自动 active，候选进入 pending 或被拒绝

### Requirement: SPEC-LTM-EXTR-006、SPEC-LTM-SEC-001、002 禁止保存与确定性敏感过滤
密码、token、API key、私钥、验证码、银行卡号、身份证号及等价凭据 MUST NOT 写入 active 或 pending。
大段原文、未经概括隐私文本、明显一次性信息和仅当前 turn 有用的内容 SHOULD 被丢弃。服务端 MUST
执行确定性凭据过滤，不能只依赖模型提示词。

#### Scenario: 候选包含凭据
- **WHEN** 模型返回含 API key 或 client secret 的候选
- **THEN** 服务端丢弃该候选，不写入 memory.json

### Requirement: SPEC-LTM-EXTR-007 用户记住与忘记指令
用户明确要求记住的内容 MAY 由非 off 会话的结构化提炼保存；敏感或低置信内容仍 SHALL pending。
结构化 disable action SHALL 按记忆 ID 停用匹配记忆。memoryPolicy=off 时自动提炼不运行，因此自然语言
“忘记”不会单独触发自动停用；用户仍 SHALL 能通过记忆 UI/API 停用或删除。

#### Scenario: 非 off 会话的 remember 候选
- **WHEN** 用户明确要求记住非敏感长期信息，提炼模型返回合法候选
- **THEN** 系统按 smart 或 confirm_all 策略保存候选

#### Scenario: 结构化 disable action
- **WHEN** 非 off 会话的提炼响应包含指向现有记忆 ID 的 disable action
- **THEN** 系统把该记忆状态更新为 disabled

#### Scenario: off 会话中的自然语言忘记
- **WHEN** 会话策略为 off 且用户只在聊天中说“忘记”
- **THEN** 自动提炼不运行，现有记忆不被隐式修改；用户可使用记忆管理入口处理

### Requirement: SPEC-LTM-EXTR-008 来源消息幂等
同一 assistant 回复重复重试或重复状态更新 MUST NOT 产生重复记忆。系统 SHALL 使用来源消息 ID 和
规范化 content 防止重复提炼与重复候选。

#### Scenario: 已处理来源消息
- **WHEN** 现有记忆已经记录当前 user/assistant sourceMessageIds
- **THEN** 后端不再次调用提炼模型

### Requirement: SPEC-LTM-EXTR-009 预算与语言
提炼调用 SHALL 计入有效 LLM 用量预算；预算阻断时本次提炼 no-op。提炼 prompt SHALL 跟随有效语言，
候选内容 MUST 保持用户原意，不得为了语言统一改变事实。

#### Scenario: 英文有效语言
- **WHEN** 应用有效语言为 English
- **THEN** 提炼 prompt 使用英文指令，同时保留来源消息语义

### Requirement: SPEC-LTM-API-001 记忆列表与筛选
`GET /desktop/memory` SHALL 返回 memories 与 legacy goals/patterns/logs，并支持 status、type、
sourceSessionId 和 q 筛选。未提供筛选时 SHALL 返回所有未物理删除记忆，按 updatedAt 降序。

#### Scenario: 搜索与状态筛选
- **WHEN** 客户端按 q 和 status 查询
- **THEN** 返回同时匹配规范化文本与状态的记忆，并保留 legacy 区块

### Requirement: SPEC-LTM-API-002、006 手动创建记忆
`POST /desktop/memory` SHALL 创建 ui_manual 记忆；`POST /desktop/chat/sessions/{id}/memory` SHALL
创建 chat_manual 记忆并自动填充 sourceSessionId。服务端 SHALL 生成 ID、时间戳和默认 confidence=10；
敏感或需确认内容 MAY 保存为 pending。

#### Scenario: 从会话手动添加
- **WHEN** 用户在会话记忆面板提交合法短句
- **THEN** 服务端创建 source=chat_manual、关联当前 session 的记忆

### Requirement: SPEC-LTM-API-003、004 记忆更新与删除
`PUT /desktop/memory/{id}` SHALL 允许修改 type、content、evidence、confidence、status 和 sensitive，
支持 pending→active、pending→rejected、active→disabled、disabled→active。`DELETE` SHALL 物理删除
目标记忆。不存在 ID SHALL 返回 404，非法字段或状态 SHALL 返回 400。

#### Scenario: 编辑后批准
- **WHEN** 用户修改 pending 候选内容并批准
- **THEN** 服务端规范化字段、更新为 active 并保存

### Requirement: SPEC-LTM-API-005 会话策略接口
`PUT /desktop/chat/sessions/{id}/memory-policy` SHALL 设置 smart、confirm_all 或 off，并返回更新后的
会话数据；非法策略返回 400，不存在会话返回 404。

#### Scenario: 关闭自动提炼
- **WHEN** 用户把会话 memoryPolicy 更新为 off
- **THEN** 后续 assistant sent 不调度自动提炼

### Requirement: SPEC-LTM-API-007..009 原子保存、串行修改与错误隔离
所有记忆新增、更新和删除 SHALL 在进程内串行修改 profile，并通过 MemoryStore save 原子替换文件。
直接 memory 写接口保存失败 SHALL 返回 500，并恢复或避免长期内存/磁盘不一致。后台提炼、去重或候选
保存失败 MUST NOT 传播为聊天、会话消息或桌面 summary 失败。

#### Scenario: 直接写接口保存失败
- **WHEN** 用户调用 memory mutation 且文件保存失败
- **THEN** API 返回 500，不报告成功写入

#### Scenario: 后台提炼保存失败
- **WHEN** 聊天已成功但异步候选保存失败
- **THEN** 聊天结果保持成功，错误被隔离到记忆提炼流程

### Requirement: SPEC-LTM-FE-001..008 桌面记忆管理体验
桌面会话页 SHALL 提供当前会话策略、pending 数量、相关记忆摘要、手动添加、编辑、批准、拒绝、停用、
重新启用和删除操作；这些操作 MUST 调用后端而非只改前端状态。每条候选 SHALL 显示来源或 evidence。
空、加载、保存失败状态 SHALL 有明确本地化文案，失败 MUST NOT 清空编辑文本。记忆管理 MUST 位于
会话界面而不是 config.toml 编辑器。

#### Scenario: 候选批准失败
- **WHEN** 用户批准候选但后端保存失败
- **THEN** 页面显示错误并保留候选与编辑内容，不伪造 active 状态

### Requirement: SPEC-LTM-SEC-003..005 用户控制与提炼隐私
rejected、disabled 和 pending 记忆 MUST NOT 进入 Agent 上下文；删除项 MUST 从 memory.json 移除。
UI SHALL 允许用户停用或删除错误记忆。提炼输入 MUST NOT 包含配置密钥，并 SHALL 遵守会话消息和
摘要的敏感数据边界。

#### Scenario: 删除错误记忆
- **WHEN** 用户删除一条错误或敏感记忆
- **THEN** 该内容不再出现在存储、下一轮 Agent 上下文或自动去重依据中

### Requirement: SPEC-LTM-NON-001..006 功能边界与事件来源
长期记忆 SHALL 保持本地文件能力，不提供云同步、账号体系、跨设备合并、向量召回或 embedding 检索。
系统 MUST NOT 自动把原始采集事件或全部聊天原文复制为长期记忆；legacy goals、patterns、
logs 不要求迁移成 MemoryItem，但 SHALL 继续兼容读取和上下文展示。

#### Scenario: 原始采集事件
- **WHEN** 系统存在新的窗口、AFK 或内容事件
- **THEN** 这些原始事件不会自动写入长期记忆；行为推断只能经有界候选和确认策略处理
