# LLM Token 预算规格

## Purpose

定义 Agent、摘要和 embedding 的 token 用量、真实 usage/估算计量、本地日期持久化、每日预算模式、结构性限流和桌面用量可见性。

## Requirements

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
Agent SHALL 以 llm.agent.maxIters 作为最大 reasoning/tool 轮数。桌面 summary 的现场 LLM 增强 SHALL 只作用于未闭合当前窗条目，且此类增强次数不得超过 `desktop.summary.maxTimelineLlm`；已结束时段的 Wiki 摘要不计入该上限，也 MUST NOT 因此再次调用 LLM。0 SHALL 禁用当前窗现场 LLM 增强，但不阻止读取已有 Wiki 摘要或快照。

#### Scenario: timeline 增强上限
- **WHEN** summary 有 20 条 timeline 且上限为 4，其中仅 2 条属于未闭合当前窗
- **THEN** 现场 LLM 最多只增强这 2 条当前窗，已结束 Wiki 条目不发起 LLM 也不计入上限

#### Scenario: 关闭当前窗 LLM
- **WHEN** `desktop.summary.maxTimelineLlm` 为 0
- **THEN** 当前窗使用本地事实或快照文案，已结束 Wiki 摘要仍可展示

### Requirement: SPEC-BUDGET-API-001..003 用量可见性
`GET /desktop/usage` SHALL 返回 date、mode、dailyTokens、warnRatio、status、totalTokens 和按类别
input/output/calls 明细。UsageMeter 或 Agent 不可用时 SHALL 返回安全降级对象，不使接口报错。
ConfigTools SHALL 展示 maxIters、summary timeline 限额和预算配置。

#### Scenario: 查询当天用量
- **WHEN** 客户端请求 desktop usage
- **THEN** 返回当前本地日期和 AGENT/SUMMARY/EMBEDDING 分类明细

### Requirement: SPEC-BUDGET-NON-001..004 预算功能边界
全局每日计量 MUST NOT 声称提供货币成本、跨天/月滚动账单、导出或分布式多进程配额。Embedding 当前 SHALL 仅计量、不执行 block gate；全局每日预算权威限于当前进程和当天本地 usage 文件。Wiki 同一逻辑周期的独立准入账本 SHALL 按周期累计，不改变全局每日计量的上述边界。

#### Scenario: 两个独立进程
- **WHEN** 两个进程使用同一或不同 memoryDir
- **THEN** 全局每日计量不保证跨进程原子共享预算；共用同一本地 Wiki 周期账本的请求仍受该账本的原子周期准入约束

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

### Requirement: SPEC-BUDGET-WIKI-001 周期预留与每日计量协作
Wiki周期账本 SHALL 在请求前原子预留一次调用与估计token额度，成功/失败/取消均至多结算一次；优先结算真实usage，缺失或崩溃不确定时保守保留预留。已确认在发出请求前被模型配置或全局预算拦截的工作不得伪记模型调用。账本与全局按执行日期的类别计量 SHALL 分清职责，不声称两个存储具有跨库原子性。

#### Scenario: 输出超过预留
- **WHEN** 服务端真实token用量超过发起时的准入预留
- **THEN** 记录完整实际用量并阻止后续超额调用，不截断计量或恢复输出上限参数

#### Scenario: 未知结算
- **WHEN** 请求发出后进程崩溃，无法知道模型是否完成
- **THEN** 请求的调用数和预留仍占周期额度，重启不重复返还或重复结算

#### Scenario: 供应商可重试错误
- **WHEN** 单次正式摘要请求遭遇429、5xx或传输失败
- **THEN** 不在同一次预留下隐式重复发送HTTP请求，后续尝试必须重新经过周期准入

#### Scenario: 正式摘要配置
- **WHEN** 普通摘要或本轮效果复验通过正式plain调用入口生成
- **THEN** 保持temperature=0.2及不发送输出上限的现行行为，使用计量预留不等同于限制服务端输出

#### Scenario: 被包装的供应商错误
- **WHEN** 摘要SDK将HTTP失败包装在异常链中，或响应正文含有与真实状态不同的数字
- **THEN** 优先使用结构化HTTP状态分类，不从正文猜测认证或重试原因，也不回显响应正文
