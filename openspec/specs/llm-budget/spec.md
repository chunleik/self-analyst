# LLM Token 预算规格

## Purpose

定义 Agent、摘要和 embedding 的 token 上限、真实 usage/估算计量、本地日期持久化、每日预算模式、结构性限流和桌面用量可见性。

## Requirements

### Requirement: SPEC-BUDGET-GOAL-001..005 用量控制目标
系统 SHALL 支持单次模型输出 token 上限、可配置 Agent 最大迭代数、按本地日期持久化的真实 token
计量、每日预算 off/warn/block 行为，以及当天用量与状态查询。预算只以 token 计，不承诺金额核算、
月度账单或多进程共享配额。

#### Scenario: 重启后查询当天用量
- **WHEN** 当天已记录模型调用后应用重启
- **THEN** UsageMeter 载入当天持久化计数，并由 usage API 返回

### Requirement: SPEC-BUDGET-CFG-001..003 预算与结构旋钮配置
`llm.max-tokens` 默认 2048，正值 SHALL 应用于 Agent 与 plain/summary 模型，0 表示不限。
`llm.agent.maxIters` 默认 8；`desktop.summary.maxTimelineLlm` 默认 4。预算 mode 默认 warn，dailyTokens
默认 100000000，warnRatio 默认 0.8。非法 mode SHALL 回退 warn，非法 warnRatio SHALL 回退 0.8。
wiki.backfill.enabled SHALL 默认 false。修改这些启动期配置后 SHALL 提示重启。

#### Scenario: maxTokens 正值
- **WHEN** llm.max-tokens 配置为 64 并重启 Agent
- **THEN** 对话与 plain 模型 GenerateOptions 使用 64 输出上限

#### Scenario: 非法预算配置
- **WHEN** mode 未知或 warnRatio 超出有效范围
- **THEN** 系统使用 warn 与 0.8 安全默认值

### Requirement: SPEC-BUDGET-MTR-001..003 线程安全日计量与持久化
UsageMeter SHALL 线程安全地按本地日期和 AGENT/SUMMARY/EMBEDDING 类别累计 input tokens、output tokens
和调用次数，并持久化到 `{memoryDir}/usage/usage-YYYY-MM-DD.json`。启动 SHALL 载入当天值；日期变化
SHALL 滚动到新文件和零计数。持久化 MAY 节流，但显式 flush/关闭 SHALL 写出最新状态。

#### Scenario: 并发类别计量
- **WHEN** Agent、summary 和 embedding 并发记录 usage
- **THEN** 各类别计数无丢失，totalTokens 等于分类 input/output 总和

#### Scenario: 本地日期变化
- **WHEN** UsageMeter 检测到当前本地日期不同于已载入日期
- **THEN** 新日期从零开始计量并写入新日期文件

### Requirement: SPEC-BUDGET-MTR-004..006 真实 usage 与保守估算
plain/summary、Agent 和 embedding 路径 SHALL 优先读取供应商返回的 usage。Agent 每次 model call SHALL
读取 ModelCallEndEvent ChatUsage；embedding SHALL 使用 prompt_tokens，缺失时 MAY 回退 total_tokens。
供应商没有 usage 时，Agent SHALL 基于消息、工具 schema 和 text/thinking/tool-call 增量保守估算。
完成、异常、取消和同步抛错路径 MUST 对同一调用至多记录一次。

#### Scenario: Agent 返回真实 usage
- **WHEN** model call end 提供 input/output token
- **THEN** AGENT 类别记录服务端值，不再使用文本估算

#### Scenario: Agent 不返回 usage
- **WHEN** 模型只产生增量事件而没有 ChatUsage
- **THEN** 系统估算输入与已生成输出并记录一次

### Requirement: SPEC-BUDGET-ENF-001 状态判定
dailyTokens<=0 时状态 SHALL 为 OK。当天 totalTokens 达到 dailyTokens 时 SHALL 为 EXCEEDED；达到
dailyTokens*warnRatio 且未超额时 SHALL 为 WARN；否则 OK。

#### Scenario: 达到 warning 比例
- **WHEN** totalTokens 达到预算的 warnRatio 但低于上限
- **THEN** snapshot status 为 warn

### Requirement: SPEC-BUDGET-ENF-002..005 off、warn 与 block
off SHALL 只计量且不告警/拦截；warn SHALL 在首次进入 WARN/EXCEEDED 时记录告警但不拦截；block
在 EXCEEDED 时 SHALL 使 enforce 抛出预算异常且 isBlocked=true。Agent 入口在 block+超额时 SHALL 返回
本地友好提示且不调用模型；对话循环在本次调用后达到预算时 SHALL 追加 stop event 提前终止后续迭代。

#### Scenario: block 前置拦截
- **WHEN** 新聊天开始时 mode=block 且当天已 EXCEEDED
- **THEN** Agent 返回本地预算提示，不发起模型调用

#### Scenario: 循环中达到预算
- **WHEN** 一个 model call 计量后使预算进入 block exceeded
- **THEN** 既有事件继续返回，middleware 追加 RequestStopEvent 停止后续迭代

### Requirement: SPEC-BUDGET-ENF-006、007 后台与页面降级
Wiki 摘要或长期记忆等后台 LLM 工作遇到预算阻断时 SHALL 保持可重试/no-op，不把预算异常计为普通
生成失败或影响主流程。桌面 summary 在 block+超额时 SHALL 使用 localOnly 汇总。metadata-only 文件
采集不调用 LLM，因此不受预算 gate 影响。embedding 调用 SHALL 计量但当前不因预算 block 被 enforce。

#### Scenario: Wiki due entry 被预算阻断
- **WHEN** Wiki worker 准备摘要但 SUMMARY 预算调用被阻止
- **THEN** entry 保持可重试状态，不写伪摘要

#### Scenario: summary 页面预算阻断
- **WHEN** 用户请求桌面 summary 且预算已 block
- **THEN** 返回本地统计和时间线，不调用 LLM 增强

### Requirement: SPEC-BUDGET-KNOB-001、002 结构性限流
Agent SHALL 以 llm.agent.maxIters 作为最大 reasoning/tool 轮数。桌面 summary SHALL 最多对前
desktop.summary.maxTimelineLlm 条时间线做 LLM 增强，其余保持 localOnly；0 SHALL 禁用逐条 LLM 增强。

#### Scenario: timeline 增强上限
- **WHEN** summary 有 20 条 timeline 且上限为 4
- **THEN** 最多 4 条进入 LLM 改写，其余使用本地结果

### Requirement: SPEC-BUDGET-API-001..003 用量可见性
`GET /desktop/usage` SHALL 返回 date、mode、dailyTokens、warnRatio、status、totalTokens 和按类别
input/output/calls 明细。UsageMeter 或 Agent 不可用时 SHALL 返回安全降级对象，不使接口报错。
ConfigTools SHALL 展示 maxTokens、maxIters、summary timeline 限额和预算配置。

#### Scenario: 查询当天用量
- **WHEN** 客户端请求 desktop usage
- **THEN** 返回当前本地日期和 AGENT/SUMMARY/EMBEDDING 分类明细

### Requirement: SPEC-BUDGET-NON-001..004 预算功能边界
预算系统 MUST NOT 声称提供货币成本、跨天/月滚动账单、导出或分布式多进程配额。Embedding 当前
SHALL 仅计量、不执行 block gate；预算权威限于当前进程和当天本地 usage 文件。

#### Scenario: 两个独立进程
- **WHEN** 两个进程使用同一或不同 memoryDir
- **THEN** 当前规格不保证跨进程原子共享预算
