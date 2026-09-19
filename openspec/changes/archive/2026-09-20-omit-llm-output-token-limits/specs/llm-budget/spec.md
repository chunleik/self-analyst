## MODIFIED Requirements

### Requirement: SPEC-BUDGET-GOAL-001..005 用量控制目标
系统 SHALL 支持可配置 Agent 最大迭代数、按本地日期持久化的真实 token
计量、每日预算 off/warn/block 行为，以及当天用量与状态查询。预算只以 token 计，不承诺金额核算、
月度账单或多进程共享配额。

#### Scenario: 重启后查询当天用量
- **WHEN** 当天已记录模型调用后应用重启
- **THEN** UsageMeter 载入当天持久化计数，并由 usage API 返回

### Requirement: SPEC-BUDGET-CFG-001..003 预算与结构旋钮配置
`llm.max-tokens` SHALL 退役，普通模型与连接测试 MUST NOT 发送输出上限参数；实际输出限制由服务端决定。
`llm.agent.maxIters` 默认 8；`desktop.summary.maxTimelineLlm` 默认 4。预算 mode 默认 warn，dailyTokens
默认 100000000，warnRatio 默认 0.8。非法 mode SHALL 回退 warn，非法 warnRatio SHALL 回退 0.8。
wiki.backfill.enabled SHALL 默认 false。上述仍有效的启动期配置变更 SHALL 提示重启，模型热更新 MUST NOT 顺带应用这些待重启配置。

#### Scenario: maxTokens 正值
- **WHEN** 通过应用保存 llm.max-tokens 为 64 后启动新的 LLM 工作
- **THEN** 该旧键按退役键保留原文但不生效，对话与 plain 模型均不发送输出上限

#### Scenario: 非法预算配置
- **WHEN** mode 未知或 warnRatio 超出有效范围
- **THEN** 系统使用 warn 与 0.8 安全默认值

#### Scenario: 解除输出上限
- **WHEN** 用户通过应用将 llm.max-tokens 保存为 0
- **THEN** 该旧键不参与有效配置，新 LLM 工作不发送输出上限；移除或修改旧值不触发模型重建

### Requirement: SPEC-BUDGET-API-001..003 用量可见性
`GET /desktop/usage` SHALL 返回 date、mode、dailyTokens、warnRatio、status、totalTokens 和按类别
input/output/calls 明细。UsageMeter 或 Agent 不可用时 SHALL 返回安全降级对象，不使接口报错。
ConfigTools SHALL 展示 maxIters、summary timeline 限额和预算配置。

#### Scenario: 查询当天用量
- **WHEN** 客户端请求 desktop usage
- **THEN** 返回当前本地日期和 AGENT/SUMMARY/EMBEDDING 分类明细
