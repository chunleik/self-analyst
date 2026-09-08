## MODIFIED Requirements

### Requirement: SPEC-BUDGET-CFG-001..003 预算与结构旋钮配置
`llm.max-tokens` 默认 2048，正值 SHALL 应用于 Agent 与 plain/summary 模型，0 表示不限。
`llm.agent.maxIters` 默认 8；`desktop.summary.maxTimelineLlm` 默认 4。预算 mode 默认 warn，dailyTokens
默认 100000000，warnRatio 默认 0.8。非法 mode SHALL 回退 warn，非法 warnRatio SHALL 回退 0.8。
wiki.backfill.enabled SHALL 默认 false。llm.max-tokens SHALL 在应用保存后的新 LLM 工作中生效；
其它上述启动期配置变更 SHALL 提示重启，模型热更新 MUST NOT 顺带应用这些待重启配置。

#### Scenario: maxTokens 正值
- **WHEN** 通过应用保存 llm.max-tokens 为 64 后启动新的 LLM 工作
- **THEN** 对话与 plain 模型使用 64 输出上限，已开始的工作保留原上限

#### Scenario: 非法预算配置
- **WHEN** mode 未知或 warnRatio 超出有效范围
- **THEN** 系统使用 warn 与 0.8 安全默认值

#### Scenario: 解除输出上限
- **WHEN** 用户通过应用将 llm.max-tokens 保存为 0
- **THEN** 新 LLM 工作不设置该输出上限，旧工作不受影响

## ADDED Requirements

### Requirement: SPEC-BUDGET-LIVE-001 模型切换保留用量
模型切换与首次配置恢复 MUST NOT 清零或重复载入覆盖当前进程的日用量。新旧模型并行期间的实际调用
SHALL 按现有 AGENT、SUMMARY 类别累计，保持真实 usage 优先、缺失时估算及每次调用至多记录一次。
仅校验、构建模型及发布配置 MUST NOT 计为模型调用，也 MUST NOT 改变现有预算模式和阻断状态。

#### Scenario: 切换时存在未结算调用
- **WHEN** 保存前的聊天和保存后的摘要分别结束
- **THEN** 两次用量分别计入对应类别，已有当天计数保留，不遗漏或重复计量

#### Scenario: 已达到预算上限
- **WHEN** 预算处于 block exceeded 且用户切换模型
- **THEN** 预算仍然阻止后续受控调用，不通过切换模型重置预算
