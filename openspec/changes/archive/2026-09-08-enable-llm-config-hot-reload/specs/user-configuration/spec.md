## MODIFIED Requirements

### Requirement: SPEC-CFGUI-UI-001..005 配置编辑器交互
配置入口 SHALL 打开单个等宽、多行、可滚动纯文本编辑器，不展示结构化字段表单。打开时 SHALL 读取
raw API；文件缺失或为空时 SHALL 显示完整注释模板但不自动写盘。加载失败 SHALL 使编辑器只读。
脏状态 SHALL 按当前文本与最近成功基准逐字符比较；放弃恢复基准，保存期间禁止重复提交。保存成功
SHALL 更新基准并显示 restartRequired/unknownKeys 及各组件的运行时应用结果；未保存成功时 SHALL
保留脏态和文本。不可用状态或仍有旧版本任务时 MUST 给出准确说明，不将“已保存”解释为全部组件已切换。
来源与运行状态 SHALL 作为只读辅助信息呈现，不替换原文编辑器。存在未保存修改时关闭
SHALL 先确认。LLM/Embedding 连接测试 SHALL 使用编辑器当前 TOML，缺失密钥可按后端语义回退有效配置。

#### Scenario: 打开空配置
- **WHEN** config.toml 不存在或为空
- **THEN** 编辑器显示合法注释模板，exists/path 状态准确，磁盘仍不创建文件

#### Scenario: 保存校验失败
- **WHEN** 当前文本有 TOML 语法或类型错误
- **THEN** 页面显示完整错误并保留编辑文本、脏态与旧磁盘文件

#### Scenario: 关闭脏编辑器
- **WHEN** 当前文本不同于基准且用户点击关闭或遮罩
- **THEN** 页面先确认，不静默丢弃修改

#### Scenario: 运行中修改模型
- **WHEN** 当前回答尚未结束且用户成功保存新的 LLM 模型配置
- **THEN** 编辑器显示保存成功、新一轮采用新配置及当前回答继续使用旧配置；不得提示全部请求已切换

#### Scenario: 未配置有效密钥
- **WHEN** 保存后有效 LLM 密钥为空或占位值
- **THEN** 界面更新已保存基准并显示 LLM 不可用及配置指引，不显示连接成功

### Requirement: SPEC-CFGUI-API-001..003 raw 与兼容 API
`GET /desktop/config/raw` SHALL 返回 text、path、exists；文件缺失/空时 text 为模板。`PUT` SHALL 在写盘
前完成 TOML 语法、结构、已知键类型和文件过滤语义校验；通过后逐字替换文件，响应 SHALL 包含 saved、
restartRequired、unknownKeys，并 SHALL 附带保存版本和运行时应用结果。任何校验、候选运行资源准备或写入失败 MUST NOT 替换目标或报告成功。结构化
`GET/PUT /desktop/config`、LLM/Embedding 测试端点和 ConfigTools SHALL 保持兼容并使用同一存储。

#### Scenario: raw 保存未知键
- **WHEN** TOML 合法但包含未知点分键
- **THEN** 文本可保存，响应在 unknownKeys 中报告该键

#### Scenario: raw 写入失败
- **WHEN** 临时文件或替换目标失败
- **THEN** 原配置保持不变，API 返回错误且 saved 不为 true

### Requirement: SPEC-TOML-LOAD-002 加载优先级
配置 SHALL 按用户 TOML 显式值、支持入口的环境变量、classpath defaults、硬编码默认值顺序
解析。未配置或删除用户覆盖后 SHALL 恢复环境变量兜底；显式空值 MUST NOT 被环境变量覆盖，
其合法性与默认值处理 SHALL 保持各键的既有规则。memory.dir 的显式 JVM property SHALL 继续优先于 TOML。
应用启动、有效配置查询、保存差异计算与连接测试 SHALL 复用同一解析规则；用户覆盖原文 SHALL
与解析后的有效配置分开呈现。aw.port MUST 只由用户 TOML 或
默认值决定，不接受环境变量覆盖。

#### Scenario: 用户配置覆盖环境变量
- **WHEN** 同一支持键同时存在环境变量和 TOML 值
- **THEN** 用户 TOML 值生效

#### Scenario: 删除覆盖恢复环境变量
- **WHEN** 用户删除一个支持环境变量的 TOML 配置项
- **THEN** 后续加载使用非空环境变量；环境变量未设置时使用内置默认值

#### Scenario: 连接测试与配置加载一致
- **WHEN** 连接测试未显式提供模型连接参数
- **THEN** LLM 与 Embedding 测试使用当前已保存配置按同一优先级解析的参数；未配置独立 Embedding 密钥时沿用有效 LLM 密钥

#### Scenario: aw.port 环境变量
- **WHEN** 环境变量尝试设置 aw.port 而 TOML 另有值
- **THEN** Java 使用 TOML/default 端口，不接受环境覆盖

#### Scenario: 仅由环境变量配置模型
- **WHEN** 用户 TOML 未设置模型而环境变量提供模型值
- **THEN** 有效配置查询与 Agent 配置工具显示环境变量模型及其来源，不误显示内置默认值

#### Scenario: 显式恢复为内置默认模型
- **WHEN** 环境变量提供非默认模型，用户通过结构化接口或 Agent 配置工具显式设置内置默认模型
- **THEN** 系统保存该 TOML 覆盖，并使后续有效配置采用用户显式模型，不因其等于内置默认值而忽略写入

#### Scenario: 删除覆盖后的运行配置
- **WHEN** 用户通过应用内保存删除 LLM 配置项并存在对应环境变量
- **THEN** 后续新工作采用环境变量兜底值，应用结果根据实际解析后的变化报告

### Requirement: SPEC-TOML-API-002 结构化端点与 ConfigTools
结构化配置端点和 ConfigTools SHALL 继续使用点分键、后端白名单、统一生效策略、应用结果及同一 config.toml。
LLM 与 Embedding 连接测试端点 SHALL 保持可用。结构化写入 MAY 重排并重生成 TOML，但 MUST 产生与
输入 flat map 等价的可解析结果。

#### Scenario: Agent 修改配置
- **WHEN** ConfigTools 设置允许键
- **THEN** 值经统一校验写入 config.toml，并返回新工作生效、需重启或不可用等准确结果

#### Scenario: Agent 在当前轮修改模型
- **WHEN** Agent 配置工具在正在执行的聊天轮次中保存新模型
- **THEN** 保存及时返回，当前轮继续完成，下一轮采用新模型，不等待当前轮结束而产生自锁

#### Scenario: 三种保存入口拒绝非法模型参数
- **WHEN** raw、结构化或 Agent 工具提交类型错误、非有限或超出 0..2 的温度、负输出上限或不可用作 HTTP(S) 基础地址的值
- **THEN** 系统按相同规则拒绝，保留原文件和当前运行配置；缺失或空密钥按未配置状态处理

## ADDED Requirements

### Requirement: SPEC-CFG-LIVE-001 LLM 应用范围与组件差异
应用内保存 SHALL 支持 llm.api-key、llm.base-url、llm.model、llm.temperature 和 llm.max-tokens
对新工作生效。温度变更 SHALL 影响聊天模型，plain/summary 与压缩 SHALL 保持既有低温策略。
系统 SHALL 按解析后的组件配置比较差异；只有注释或来源变化而有效模型参数相同时 MUST NOT 重建模型。
非热更新组件的重启提示 SHALL 对比其实际运行配置与最新已保存配置，恢复原值后 SHALL 清除该待重启提示。
配置生效策略 SHALL 由后端集中声明，桌面接口和 Agent 工具 MUST 使用同一声明。

#### Scenario: 同时保存模型与端口
- **WHEN** 一次保存同时修改 LLM 模型与服务端口
- **THEN** 新 LLM 工作采用新模型，端口保持当前监听值并列为需重启，不因重建模型提前应用端口或其它启动期配置

#### Scenario: 仅修改注释
- **WHEN** 用户只修改 TOML 注释或空行后保存
- **THEN** 原文逐字保存，模型运行版本不变化，不产生额外连接或重启提示

#### Scenario: 恢复待重启配置
- **WHEN** 用户将尚未生效的启动期配置改回当前运行值
- **THEN** 对应待重启提示消失，其它尚未生效的组件提示继续保留

#### Scenario: Embedding 继承密钥变化
- **WHEN** 已运行的 Embedding 客户端继承 LLM 密钥，用户改变有效 LLM 密钥
- **THEN** 新 LLM 工作采用新密钥，Embedding 继续使用当前运行配置，结果明确指出 Embedding 密钥差异需重启；未运行的 Embedding MUST NOT 被自动启用

#### Scenario: 外部编辑文件
- **WHEN** 用户仅通过外部编辑器修改 config.toml，未经过应用保存入口
- **THEN** 本期不保证运行时自动切换；后续启动或应用内保存 SHALL 重新解析当前文件

### Requirement: SPEC-CFG-LIVE-002 有效配置和应用结果可见性
系统 SHALL 提供受既有桌面认证保护的 GET /desktop/config/effective，区分最新已保存配置及来源、
新工作将使用的 LLM 版本、尚在使用旧版本的工作，以及其它组件待重启差异。来源 SHALL 区分 TOML、
环境变量、JVM property、默认值及继承值。普通配置查询、运行状态、日志及错误中的密钥 MUST 脱敏，
MUST NOT 输出密钥派生的可比对摘要；已认证 raw 原文编辑的既有边界保持不变。
保存响应 SHALL 保留兼容字段，并提供保存版本、LLM 运行版本和组件应用结果；saved=true 只表示
本次持久化成功，MUST NOT 作为远端模型可调用或全部组件已切换的证明。
应用结果 SHALL 区分已应用、仍有旧版本工作、需重启和未配置不可用；应用失败 SHALL 通过失败响应报告。

#### Scenario: 新旧版本同时存在
- **WHEN** 保存成功后一个旧版本回答仍在运行
- **THEN** 查询显示新工作采用的版本及仍有旧版本工作；旧工作结束后相应状态消失

#### Scenario: 重新打开配置
- **WHEN** 用户关闭后重新打开配置编辑器
- **THEN** 仍可看到当前生效来源及尚未生效的组件，不依赖上一条保存提示

#### Scenario: 非 raw 查询密钥
- **WHEN** 已认证客户端读取有效配置，或发生包含密钥的底层异常
- **THEN** 响应和日志只提供脱敏值、来源及可操作错误，不暴露密钥或原始认证请求

### Requirement: SPEC-CFG-LIVE-003 保存失败与并发一致性
配置更新 SHALL 协调校验、候选运行资源准备、持久化和版本发布。校验、候选资源准备或写盘失败时
MUST 保持原磁盘文件和当前可用运行版本，并释放候选资源。保存的本地成功 MUST NOT 依赖额外远端
连接测试；新模型后续返回认证、限流或网络错误时 SHALL 报告实际错误，MUST NOT 自动回退到旧供应商或密钥。
同进程配置写入 SHALL 串行提交，后提交的版本 MUST NOT 被较早构建的候选覆盖。关闭开始后 SHALL 拒绝
新保存且不留下已落盘但未发布的普通成功响应。本期不新增配置历史、自动恢复旧文件或跨进程事务承诺。

#### Scenario: 构建新运行资源失败
- **WHEN** 新模型运行资源无法完成本地构建
- **THEN** 保存返回失败，原配置文件和旧模型仍可使用，错误不泄露密钥

#### Scenario: 写入失败
- **WHEN** 候选资源已准备完成但配置文件替换失败
- **THEN** 保存返回失败并释放候选资源，旧文件和旧运行版本保持不变

#### Scenario: 连续提交两个模型
- **WHEN** 两个保存请求依次被接受并成功提交不同模型
- **THEN** 新工作使用后一次提交的模型，返回的版本与实际提交顺序一致

#### Scenario: 新模型返回认证失败
- **WHEN** 保存成功后新模型在实际调用中返回认证错误
- **THEN** 系统报告该调用失败，不把请求发送给旧供应商，不静默修改文件

#### Scenario: 关闭期间保存
- **WHEN** 应用已开始关闭后收到配置保存请求
- **THEN** 请求被拒绝，关闭过程不创建新模型或重启后台服务
