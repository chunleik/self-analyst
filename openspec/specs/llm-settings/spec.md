# 独立模型设置规格

## Purpose

定义桌面端独立的大模型连接设置能力，使用户无需编辑整份配置文件即可管理当前模型连接、验证草稿和发现可用模型，并明确凭据保护、配置保真及运行时应用结果的边界。

## Requirements

### Requirement: SPEC-LLMSET-001 独立设置查询与单连接范围
系统 SHALL 提供受既有桌面认证及回环来源约束保护的 `GET /desktop/llm-settings`，返回当前活动连接的 provider、API Key 连接的有效 baseUrl、model、temperature、独立的 chatgptAccount/chatgptModel、用户覆盖及其来源、凭据配置状态和运行应用结果。provider SHALL 区分 api-key 与 chatgpt，缺省保持 api-key；ChatGPT 登录状态 SHALL 通过专用接口返回受限账户信息，不返回 OAuth 令牌。凭据 MUST 仅返回 configured、source 和是否存在用户覆盖，不返回明文、掩码后缀或密钥派生摘要。查询及打开页面 MUST NOT 写盘或发起远端请求。界面 SHALL 仅提供当前运行时支持的字段，允许选择已授权 ChatGPT 账户，但同时只有一个活动模型连接。查询不得导入其它应用凭据。

#### Scenario: 仅通过环境变量配置
- **WHEN** 用户没有对应 TOML 覆盖而环境变量提供模型连接
- **THEN** 查询和界面展示实际有效值及环境来源，凭据仅显示配置状态，打开页面不固化环境值

#### Scenario: 未认证访问
- **WHEN** 请求未满足桌面认证或回环来源约束
- **THEN** 所有模型设置接口拒绝访问，不读取或修改设置，不发送探测请求

#### Scenario: 查看已授权 ChatGPT 账户
- **WHEN** 用户打开模型设置且存在多个已授权 ChatGPT 账户
- **THEN** 界面展示可选账户及受限连接状态，保持独立 API Key 配置，不返回令牌或自动发起模型请求

### Requirement: SPEC-LLMSET-002 连接预设与参数校验
系统 SHALL 通过 `GET /desktop/llm-settings/presets` 提供 OpenAI、DeepSeek、OpenRouter 和自定义 OpenAI-compatible 连接预设；另提供 ChatGPT 登录认证方式；API Key 预设 SHALL 仅辅助填写连接地址和协议，不包含密钥，不锁定模型 ID，不自动发现、测试或保存。API Key 用户 SHALL 能手工编辑地址和模型；ChatGPT 模式 SHALL 使用受信任目标和独立模型字段，不允许把 OAuth 令牌发送到任意 Base URL。保存及探测输入 MUST 校验 HTTP(S) 地址和字段类型；地址 MUST NOT 包含 URL 用户凭据、查询参数或片段。保存时当前认证方式的模型 MUST 非空，温度 MUST 为 0..2 的有限数。ChatGPT 不支持温度时 SHALL 明确说明该参数不适用，不能覆盖已保存 API Key 温度。maxTokens 为退役字段，接口 MUST 拒绝该字段的更新或继承重置。不支持的字段或协议 MUST 被明确拒绝，不能静默忽略后报告成功。

#### Scenario: 选择预设
- **WHEN** 用户选择一个连接预设
- **THEN** 只修改当前表单草稿，仍可编辑地址与模型，运行连接不变

#### Scenario: 无效输出上限
- **WHEN** 旧客户端在保存草稿或继承重置中提交 maxTokens（包括正值、零、负数或非整数）
- **THEN** 返回不支持字段或请求无效错误，保留原配置与运行实例，页面保留草稿

#### Scenario: 切换认证方式草稿
- **WHEN** 用户将草稿从 API Key 切换为 ChatGPT
- **THEN** 显示独立账户、模型与登录操作，已有 API Key 地址、模型和温度不被替换，未保存前活动连接不变

### Requirement: SPEC-LLMSET-003 凭据操作语义
API Key 保存和探测 SHALL 使用明确的凭据操作：keep 保留现有覆盖与继承关系；replace 使用新非空密钥；clear 采用显式空覆盖并阻止环境兜底；reset 移除用户覆盖并恢复既有解析优先级。只有成功保存 SHALL 持久化这些操作，探测 SHALL 仅在内存中解析相应草稿值。未提供操作 SHALL 等价 keep；界面中空白密码输入 SHALL 使用 keep，MUST NOT 将空白输入解释为清除。清除和恢复继承 SHALL 分别提供明确操作及确认。互相矛盾的操作或携带值的 keep/clear/reset MUST 被拒绝。密码输入在保存成功或关闭后 SHALL 清空，不进入浏览器持久存储、URL、日志或错误；高级原文编辑的既有权限边界继续有效。

#### Scenario: 修改模型但未输入新密钥
- **WHEN** 用户只修改模型并保存
- **THEN** 已有密钥和来源关系保持不变，不把脱敏值写回配置

#### Scenario: 清空与恢复环境来源
- **WHEN** 存在有效环境密钥，用户先确认 clear 保存，再确认 reset 保存
- **THEN** 第一次保存后新 LLM 工作报告未配置，第二次保存后按环境兜底恢复，不以 keep 替代任一操作

ChatGPT OAuth 凭据 SHALL 由独立登录、刷新和断开操作管理，不接受 API Key replace/clear/reset 作为 OAuth 凭据修改；切换 provider MUST NOT 删除任何一种凭据。

#### Scenario: ChatGPT 模式保留 API Key
- **WHEN** 用户保存 ChatGPT 账户和模型选择
- **THEN** 未显式修改的 API Key 配置保持不变，OAuth 令牌不进入定向 TOML 更新

### Requirement: SPEC-LLMSET-004 定向保存与运行应用
`PUT /desktop/llm-settings` SHALL 接受局部字段更新、字段恢复继承及凭据操作，只修改既有四个 LLM 连接与生成参数配置键及 llm.provider、llm.chatgpt-account、llm.chatgpt-model；未提交字段 MUST 保留原覆盖关系。新入口 SHALL 保留未修改配置原文、注释、空行和换行风格，不整文件重新生成。对无法安全定位的合法复杂 TOML SHALL 明确拒绝定向编辑并指引高级配置；不能破坏原文或静默丢失内容。文件不存在时只在成功保存后创建实际覆盖，MUST NOT 将全部默认值写入文件。保存 SHALL 复用统一配置校验、解析优先级及运行版本提交语义；任何失败保持原文件和当前运行实例。响应 SHALL 区分持久化成功、已应用、旧工作仍在运行、未配置及其它组件需重启，保存成功不等价连接测试成功。

#### Scenario: 保留其它配置与注释
- **WHEN** 用户只修改模型，原文件含中文注释、文件监控设置和未知合法键
- **THEN** 仅目标赋值发生必要变化，其它原文保留；原有配置值及覆盖关系不变

#### Scenario: 模型与运行工作切换
- **WHEN** 一个回答仍使用旧模型时用户保存新模型
- **THEN** 保存及时完成，新工作采用新模型，旧回答继续使用原版本，界面显示旧工作状态直到结束

#### Scenario: 同进程交错保存
- **WHEN** Agent 或其它配置接口先成功修改非 LLM 配置，随后模型表单保存局部更新
- **THEN** 后一次提交基于最新文件处理，只更新指定键，不覆盖先前非 LLM 更新

#### Scenario: 无法安全更新或写盘失败
- **WHEN** TOML 无法安全定向编辑，或候选资源准备、校验、写盘失败
- **THEN** 保存返回可操作错误，旧文件及运行版本保持不变，前端保留草稿

#### Scenario: 保存 ChatGPT 连接
- **WHEN** 用户保存所选 ChatGPT 账户和模型
- **THEN** 仅对应非敏感配置键写入 TOML，新工作使用该选择，原有 API Key 连接字段及当前工作保持不变，保存成功不证明账户具备推理权限

### Requirement: SPEC-LLMSET-005 草稿模型发现
API Key 的 `POST /desktop/llm-settings/discover-models` 与 ChatGPT 专用发现接口 SHALL 按当前草稿、所选账户及对应凭据操作请求目标服务的模型目录，只返回去重后的有效模型 ID 和结果状态，不持久化目录或猜测模型容量。失败或不支持目录时 SHALL 保留手工模型输入能力及原草稿。发现操作 MUST NOT 保存设置或切换运行模型。界面 SHALL 只在用户主动点击时请求，连接参数、provider 或 ChatGPT 账户变化后旧结果 MUST 失效；过期响应不能覆盖新结果。

#### Scenario: 发现并选择模型
- **WHEN** 模型目录返回重复 ID 和无效条目
- **THEN** 界面提供去重有效候选，忽略无效条目，用户选中后只更新模型草稿

#### Scenario: 服务不支持目录
- **WHEN** 模型目录请求失败或服务不支持该接口
- **THEN** 显示原因并允许手工填写模型，不能据此断言该模型生成不可用

#### Scenario: 切换账户后目录迟到
- **WHEN** 旧 ChatGPT 账户的模型目录尚未返回而用户选择另一账户
- **THEN** 旧结果被忽略，不覆盖新账户的模型草稿，不伪造新账户授权模型清单

### Requirement: SPEC-LLMSET-006 最小生成测试
API Key 的 `POST /desktop/llm-settings/test` 与 ChatGPT 专用测试接口 SHALL 使用草稿中的目标模型和连接发送固定短提示，并返回非流式的最终测试结果；ChatGPT 上游使用 Responses 协议，MUST NOT 发送任何输出上限参数，返回成功与否、受限错误分类和耗时。只有成功响应包含符合协议的生成结果才能报告通过，HTTP 成功或目录可访问本身不足以证明生成成功。测试前界面 SHALL 提示会产生模型服务端决定的用量和相应计费或订阅额度消耗；测试 MUST NOT 携带会话、标题事实、文件信息或业务提示词，MUST NOT 保存配置、启动业务任务或切换运行实例。旧 `/desktop/config/test-llm` SHALL 保留兼容行为，界面明确区分目录连通检查与新生成测试。

#### Scenario: 目录可访问但模型无法生成
- **WHEN** 目录接口可访问而所选模型生成返回认证、权限、限流或模型错误
- **THEN** 新测试报告对应失败，不能沿用目录检查成功状态

#### Scenario: 生成测试成功
- **WHEN** 草稿指向的模型返回符合协议的生成结果
- **THEN** 页面显示该草稿测试成功和耗时，配置文件及当前运行版本均不变

#### Scenario: 测试省略输出上限
- **WHEN** 用户测试当前草稿，旧配置仍包含 llm.max-tokens
- **THEN** 实际生成请求不含 max_tokens、max_completion_tokens 或同类限制；仍遵循既有超时、响应大小和有效结果校验

#### Scenario: 已登录但无推理权限
- **WHEN** ChatGPT 账户已登录而服务端未授予直接模型访问权限
- **THEN** 测试明确提示所需权限或套餐条件，不把本地登录状态报告为生成成功，不改用已有 API Key

### Requirement: SPEC-LLMSET-007 远端调用与状态保护
两种认证方式的发现和测试 SHALL 限制超时、响应大小与返回条目数，拒绝携带凭据跟随重定向，错误和返回数据 MUST NOT 暴露请求密钥或完整远端响应。使用 keep 引用现有密钥进行探测时，草稿基础地址与已保存有效地址不同时 SHALL 要求用户重新输入目标服务密钥；不能把已有密钥自动发送到新地址。界面关闭、切换编辑视图或连接参数、provider 或账户变化后 SHALL 取消或忽略旧请求，并清除不再适用于当前草稿的测试状态。非密钥文本展示 SHALL 转义，避免模型 ID 或远端数据被解释为 HTML。

#### Scenario: 修改地址后测试
- **WHEN** 用户修改 Base URL 且未输入新密钥，尝试用 keep 探测
- **THEN** 不发送已有密钥，页面提示重新输入目标服务凭据

#### Scenario: 过期请求返回
- **WHEN** 探测发起后用户修改连接参数或关闭窗口，旧请求随后返回
- **THEN** 旧结果不显示为当前连接结果，不覆盖草稿或重新打开窗口

#### Scenario: ChatGPT 信任目标
- **WHEN** ChatGPT 模型发现或测试返回重定向到其它目标
- **THEN** 不跟随重定向传递 OAuth 凭据，界面报告受限错误并保留草稿

### Requirement: SPEC-LLMSET-008 独立界面与草稿生命周期
模型设置 SHALL 提供认证方式选择；API Key 模式提供连接预设、地址、密码输入和温度，ChatGPT 模式提供账户选择、登录状态、添加账户、取消登录及断开操作；两种模式均提供模型选择与手工输入、发现、测试、保存和放弃操作，支持正式语言资源及键盘操作。加载失败时 SHALL 禁止保存而不使用默认值伪装已读取设置；失败请求 SHALL 保留草稿并允许重试。保存期间 SHALL 禁止修改和重复提交。切换到高级配置或关闭有脏草稿时 SHALL 提供确认；用户取消确认时保留当前视图和全部草稿，确认放弃后重新加载目标视图的已保存配置。运行状态刷新 MUST NOT 覆盖草稿。

#### Scenario: 表单与高级编辑器切换
- **WHEN** 表单有未保存更改，用户切换高级配置并取消放弃确认
- **THEN** 继续显示原表单和草稿，不读取原文来替换它

#### Scenario: 首次配置
- **WHEN** 应用没有模型凭据，用户填写合法设置并成功保存
- **THEN** 无需重启即可让新 LLM 工作按已保存配置尝试调用，页面区分本地应用状态和远端测试状态

#### Scenario: 登录完成时已有模型草稿
- **WHEN** 用户编辑未保存模型草稿时 ChatGPT 登录状态发生变化
- **THEN** 仅更新账户状态与可用操作；新发起的登录成功后可将该账户选入当前草稿，但不得覆盖模型及其它字段，不自动保存模型配置

#### Scenario: 系统浏览器不可用
- **WHEN** 用户点击登录但当前环境不能安全打开授权地址
- **THEN** 界面明确报告浏览器打开限制并保留重试或取消入口，不报告登录成功，不开放任意 URL 的原生浏览器调用
