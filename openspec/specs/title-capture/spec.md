# 上下文标题采集规格

## Purpose

定义 Windows 前台窗口的标题采集能力：保留操作系统窗口元数据，在单次调用内临时使用无障碍树，保守地产生微信聊天或文章标题投影，并确保正文不进入自动采集事件和持久化链路。

## Requirements

### Requirement: SPEC-CTX-001 当前活动标题事实
系统 SHALL 保存当前前台窗口的应用名和系统窗口标题；只有受支持的微信聊天或文章结构形成可靠候选时，
才 SHALL 额外产生上下文标题。系统 MUST NOT 把无障碍正文或未验证应用的 `Document.Name` 当作标题。

#### Scenario: 受支持的微信窗口
- **WHEN** 微信窗口形成可靠聊天或文章候选
- **THEN** 系统保留系统窗口标题，并增加相应上下文标题

#### Scenario: 未验证应用的文档名称
- **WHEN** 未验证应用仅暴露看似标题的 `Document.Name`
- **THEN** 系统省略上下文标题，只保留系统窗口元数据

### Requirement: SPEC-CTX-002 瞬时读取与标题投影
无障碍节点树、控件值及拼接文本 MAY 仅在当前查询和识别调用内存在。自动采集结果和 heartbeat
MUST NOT 携带原始节点、正文或拼接文本，只可携带标题投影和不可还原正文的非负诊断计数。
无可靠候选时 SHALL 省略上下文标题。

#### Scenario: 秘密正文不跨越投影边界
- **WHEN** 临时无障碍文本包含唯一秘密标记并完成标题识别
- **THEN** 标题结果和 heartbeat 不包含该标记，且只保留候选标题及诊断计数

#### Scenario: 没有可靠标题候选
- **WHEN** 无障碍树只提供正文或无法验证的短文本
- **THEN** 系统省略上下文标题，不用正文首句或截断文本回退

### Requirement: SPEC-CTX-003 不启用替代正文识别
当前标题采集器 MUST NOT 使用截图、OCR、麦克风、系统声音或语音转写作为无障碍查询的替代或回退。
该要求不禁止服务端继续识别和迁移历史 OCR 兼容字段。

#### Scenario: 无障碍查询不可用
- **WHEN** 无障碍查询没有返回可用结果
- **THEN** 系统只保留操作系统窗口元数据，不启动其他正文识别方式

### Requirement: SPEC-CTX-010 标题候选优先级与来源
可靠的微信聊天标题 SHALL 优先于经验证的微信富文档标题；聊天界面不得回退使用 `Document.Name`。
无上下文候选时 SHALL 使用 `title_source=window`。有上下文时，`title_source` SHALL 描述上下文标题
来自 `uia_context` 或 `uia_document`，而 `title` 始终保持为系统窗口标题。

#### Scenario: 微信聊天标题优先
- **WHEN** 同一微信界面同时能够形成可靠聊天候选和可靠富文档候选
- **THEN** 系统输出 `uia_context` 来源的聊天标题，不再选择文档标题

#### Scenario: 聊天界面阻断文章回退
- **WHEN** 当前界面具有聊天表面标记但不能形成可靠聊天标题
- **THEN** 系统省略上下文标题，不把 `Document.Name` 当作文章标题

#### Scenario: 没有上下文候选
- **WHEN** 当前窗口没有可靠聊天或文章候选
- **THEN** `title` 保留系统窗口标题，`title_source` 为 `window`

### Requirement: SPEC-CTX-011 候选过滤与规范化
上下文标题 MUST 为单行、非空、最多 200 Unicode code point，且不得是纯 URL、通用应用或控件标签，
也不得是具有两个及以上句末标点的明显句群。标题 MAY 去除首尾空白并压缩内部空白，但 MUST NOT
摘要正文。

#### Scenario: 非标题文本被拒绝
- **WHEN** 候选是 URL、通用控件、多行文本、明显句群或超过长度上限
- **THEN** 系统省略该候选

#### Scenario: 标题空白规范化
- **WHEN** 有效标题只包含多余的首尾或内部空白
- **THEN** 系统规范化空白后保留标题语义，不改写或摘要文本

### Requirement: SPEC-CTX-012 微信聊天与文章语义
系统 SHALL 只对受支持的 Weixin/WeChat 进程应用微信规则。符合聊天标题栏结构的有效候选 SHALL 输出
`chat/uia_context/high`；不处于聊天界面且具有经验证富文档结构的 `Document.Name` SHALL 输出
`article/uia_document/high`。结构规则不能形成可靠候选时 SHALL 省略上下文标题。

#### Scenario: SPEC-TST-102 微信聊天标题识别
- **WHEN** 受支持进程具有完整聊天标题栏锚点和伴随控件
- **THEN** 系统提取锚点前的有效标题作为 `chat/uia_context/high` 上下文标题

#### Scenario: 微信文章标题识别
- **WHEN** 受支持进程不处于聊天界面，且具有经验证的富文档结构和有效文档名称
- **THEN** 系统输出 `article/uia_document/high` 上下文标题

#### Scenario: 不受支持的进程
- **WHEN** 前台进程不是受支持的 Weixin/WeChat 进程
- **THEN** 系统不应用微信聊天或文章规则

#### Scenario: 两类微信结构均不足
- **WHEN** 受支持进程既不能满足完整聊天规则，也不能满足完整富文档文章规则
- **THEN** 系统不猜测聊天或文章标题

### Requirement: SPEC-UIA-001 无障碍查询边界
系统 SHALL 使用当前前台窗口标识请求该窗口的临时无障碍树，并在当前请求结束后只消费标题投影。
通用 `Document.Name` 不具备标题语义保证，只有应用专用规则验证的富文档且非聊天节点才可成为候选。

#### Scenario: 通用文档名称不被采用
- **WHEN** 未受支持应用返回看似标题的 `Document.Name`
- **THEN** 系统不产生上下文标题

### Requirement: SPEC-UIA-002 查询失败降级
无障碍边车缺失、超时、崩溃、返回失败或解析失败时，系统 SHALL 把本次无障碍结果视为空，继续标题
采集循环并保留系统窗口标题。

#### Scenario: 边车失败
- **WHEN** 当前无障碍查询失败
- **THEN** 本次结果为 window-only，且后续采集轮次仍可继续

### Requirement: SPEC-UIA-003 敏感窗口排除
系统 MUST 在无障碍查询前应用内置敏感应用及密码或凭据标题规则。命中规则时 MUST 跳过控件查询，
但 MAY 保存操作系统提供的应用名和窗口标题。

#### Scenario: 敏感窗口只保留系统元数据
- **WHEN** 前台应用或标题命中内置排除规则
- **THEN** 系统不查询无障碍树，并产生 `title_source=window`、`uia_chars=0` 的投影

### Requirement: SPEC-WCH-001 窗口变化与稳定刷新
对于未命中敏感窗口排除规则的窗口，系统 SHALL 在轮询检测到窗口标识、应用名或窗口标题变化时使
旧投影失效，并在该采集轮次发起新查询；窗口稳定时 SHALL 周期性刷新。查询结果只有在查询后前台
快照仍一致时才可发布。命中排除规则的窗口 SHALL 只刷新 window-only 投影，不发起无障碍查询。

#### Scenario: 查询期间窗口发生变化
- **WHEN** 查询完成后前台窗口标识、应用名或标题已改变
- **THEN** 系统丢弃旧窗口的查询结果

#### Scenario: 稳定窗口内上下文变化
- **WHEN** 系统窗口快照保持稳定
- **THEN** 系统仍按内部固定间隔重新查询上下文标题

#### Scenario: 敏感窗口刷新
- **WHEN** 窗口发生变化或到达稳定刷新间隔，但当前窗口命中敏感排除规则
- **THEN** 系统刷新 window-only 投影，不查询无障碍树

### Requirement: SPEC-WCH-002 标题 heartbeat
标题 heartbeat SHALL 逐字段构造内容事件 v2，包含 `schema_version=2`、`app`、`title`、
`title_source`、`uia_chars`，以及仅在上下文标题存在时出现的上下文字段。生产采集器 MUST NOT
生成正文、原始树、截图引用、`title_source=ocr_title` 或字段 `ocr_chars`。

#### Scenario: heartbeat 包含可靠上下文标题
- **WHEN** 已提取可靠上下文标题
- **THEN** heartbeat 包含相应标题、类型、来源和置信度，且不含正文键

#### Scenario: heartbeat 没有上下文标题
- **WHEN** 未提取上下文标题
- **THEN** heartbeat 省略上下文字段并使用 `title_source=window`

### Requirement: SPEC-WCH-003 降级运行状态
无障碍查询失败 MUST NOT 终止采集循环。heartbeat 请求失败或返回非成功状态时，采集状态 SHALL
变为 `degraded`；后续成功时 SHALL 恢复为 `running`；采集器停止时 SHALL 为 `disabled`。
自动采集错误处理 MUST NOT 主动记录原始无障碍树、拼接文本或控件值。

#### Scenario: heartbeat 失败
- **WHEN** heartbeat 请求失败或服务端返回非成功状态
- **THEN** 标题采集继续运行，运行状态变为 `degraded`

#### Scenario: heartbeat 状态恢复
- **WHEN** degraded 状态下后续 heartbeat 成功发送
- **THEN** 运行状态恢复为 `running`
