# SelfAnalyst LLM Token 用量限制与每日预算 SDD 规格说明书

> **迁移状态：** 现行行为契约已迁移至 [`llm-budget`](../../openspec/specs/llm-budget/spec.md)。本文档仅保留为旧 ID、历史背景和源码追溯，不再独立维护。

> Specification-Driven Development spec. 本文档定义 SelfAnalyst 对大模型（LLM / embedding）token 消耗的限制与每日预算控制的行为契约。实现必须可追溯至本文档中的规格 ID。

---

## 1. 文档元信息

| 属性 | 值 |
|------|-----|
| 功能名称 | LLM Token 用量限制与每日预算 |
| 文档状态 | 已实现（当前契约） |
| 日期 | 2026-06-20 |
| 规格前缀 | `SPEC-BUDGET-*` |
| 核心组件 | `com.selfanalyst.usage.UsageMeter` |
| 跨模块契约 | `com.selfanalyst.wiki.usage.UsageRecorder`、`com.selfanalyst.wiki.usage.BudgetExceededException` |
| 主要收口点 | `SelfAnalystAgent.completePlain` / `chat`、`OpenAiCompatibleEmbeddingClient` |
| 配置入口 | `Config`、`application.properties`、`ConfigTools` |
| 后端入口 | `GET /desktop/usage` |

---

## 2. 背景和当前状态

改造前，SelfAnalyst 对 LLM token 消耗几乎没有任何控制：两个 `OpenAIChatModel` 都未设置 `max_tokens`；无任何 token 计数、配额或限流；Agent 单次对话最坏触发约 15 次 LLM 调用（`maxIters` 硬编码）；打开 Agent 页会对每条 timeline 条目各调一次 LLM；`wiki.backfill.enabled` 默认开启会在启动时逐条摘要全部历史时段。

本功能引入：(1) 一组轻量配置旋钮以直接降低单次/结构性消耗；(2) 一个真实 token 计量 + 每日预算系统，支持 off / warn / block 三种可配置超额行为。

---

## 3. 目标

- **SPEC-BUDGET-GOAL-001**: 必须能为单次 LLM 输出设置 token 上限（`max_tokens`）。
- **SPEC-BUDGET-GOAL-002**: Agent 单次对话的最大推理/工具轮数必须可配置。
- **SPEC-BUDGET-GOAL-003**: 必须基于响应中的真实 usage 计量 token，按本地日期累计并跨重启保留。
- **SPEC-BUDGET-GOAL-004**: 必须支持每日 token 预算，并提供 off / warn / block 三种超额行为。
- **SPEC-BUDGET-GOAL-005**: 必须能查询当天用量与预算状态。

## 4. 非目标

- **SPEC-BUDGET-NON-001**: 不做按金额（货币成本）核算，仅以 token 计。
- **SPEC-BUDGET-NON-002**: 不做跨天/月的滚动配额或账单导出。
- **SPEC-BUDGET-NON-003**: 不对 embedding 调用做 block 拦截（仅计量），因其成本远低于 chat。
- **SPEC-BUDGET-NON-004**: 不引入分布式/多进程共享配额；计量为单进程范围。

---

## 5. 配置项

所有项在 `Config` 中加载（环境变量 > 用户配置 > 打包默认值），并可经 `ConfigTools` 读写；修改后需重启生效。

| key | env | 默认 | 说明 |
|-----|-----|------|------|
| `llm.max-tokens` | `LLM_MAX_TOKENS` | `2048` | 单次输出 token 上限；`0` = 不限 |
| `llm.agent.maxIters` | `LLM_AGENT_MAX_ITERS` | `8` | Agent 单次对话最大轮数 |
| `desktop.summary.maxTimelineLlm` | `DESKTOP_SUMMARY_MAX_TIMELINE_LLM` | `4` | summary 页最多 LLM 增强的 timeline 条数 |
| `llm.budget.mode` | `LLM_BUDGET_MODE` | `warn` | `off` / `warn` / `block` |
| `llm.budget.dailyTokens` | `LLM_BUDGET_DAILY_TOKENS` | `100000000` | 每日总 token 上限；`0` = 不限 |
| `llm.budget.warnRatio` | `LLM_BUDGET_WARN_RATIO` | `0.8` | 触发 WARN 的占比（0~1） |

- **SPEC-BUDGET-CFG-001**: `llm.max-tokens` > 0 时必须应用到 agent 与 plain 两个模型的 `GenerateOptions`。
- **SPEC-BUDGET-CFG-002**: 非法 `llm.budget.mode` 必须回退为 `warn`；非法 `warnRatio` 必须回退为 `0.8`。
- **SPEC-BUDGET-CFG-003**: `wiki.backfill.enabled` 默认值必须为 `false`。

---

## 6. 计量 (UsageMeter)

- **SPEC-BUDGET-MTR-001**: `UsageMeter` 必须线程安全（agent、后台 worker、summary 并发池共用）。
- **SPEC-BUDGET-MTR-002**: 必须按本地日期分桶，并按 `AGENT` / `SUMMARY` / `EMBEDDING` 三类累计 input/output token 与调用次数。
- **SPEC-BUDGET-MTR-003**: 必须持久化到 `{memoryDir}/usage/usage-YYYY-MM-DD.json`，启动时载入当天值，跨天自动滚动。
- **SPEC-BUDGET-MTR-004**: `completePlain` 必须从响应 `ChatUsage` 读取真实 input/output token 并计入 `SUMMARY`。
- **SPEC-BUDGET-MTR-005**: embedding 客户端必须解析响应 `usage`（`prompt_tokens`，回退 `total_tokens`）并经 `UsageRecorder` 计入 `EMBEDDING`。
- **SPEC-BUDGET-MTR-006**: Agent 路径必须优先读取每次 `ModelCallEndEvent` 的 `ChatUsage` 并计入 `AGENT`；供应商未返回 usage 时，基于 `ModelCallInput` 的消息/工具 schema 与 text/thinking/tool-call 增量保守估算。

---

## 7. 预算与超额行为

- **SPEC-BUDGET-ENF-001**: 状态判定——当 `dailyTokens <= 0` 恒为 OK；当天总 token ≥ `dailyTokens` 为 EXCEEDED；≥ `dailyTokens * warnRatio` 为 WARN；否则 OK。
- **SPEC-BUDGET-ENF-002**: `off` 模式不告警不拦截，仅计量。
- **SPEC-BUDGET-ENF-003**: `warn` 模式在首次进入 WARN/EXCEEDED 时记录一条日志告警，但不拦截任何调用。
- **SPEC-BUDGET-ENF-004**: `block` 模式下超额时，`enforce()` 必须抛出 `BudgetExceededException`，且 `isBlocked()` 返回 true。
- **SPEC-BUDGET-ENF-005**: Agent 对话入口在 block+超额时必须返回友好提示且不发起调用；对话循环中超额必须经 hook `stopAgent()` 提前中止。
- **SPEC-BUDGET-ENF-006**: 后台 wiki/file worker 捕获 `BudgetExceededException` 后必须保持条目 PENDING（不计入失败重试次数），下个周期/次日重试。
- **SPEC-BUDGET-ENF-007**: summary 页在 block+超额时必须降级为仅本地汇总（localOnly），不抛错。

---

## 8. 结构性限流（旋钮）

- **SPEC-BUDGET-KNOB-001**: Agent 必须以 `llm.agent.maxIters` 作为最大轮数。
- **SPEC-BUDGET-KNOB-002**: `getSummary` 最多对前 `desktop.summary.maxTimelineLlm` 条 timeline 做 LLM 增强，其余走 localOnly。

---

## 9. 用量可见性

- **SPEC-BUDGET-API-001**: `GET /desktop/usage` 必须返回当天用量快照（日期、mode、dailyTokens、warnRatio、status、totalTokens、按类别明细）。
- **SPEC-BUDGET-API-002**: Agent 不可用时该接口必须返回安全降级对象，不报错。
- **SPEC-BUDGET-API-003**: `ConfigTools` 的 `getConfig` 必须展示用量限制相关配置项。

---

## 10. 验收标准

- **SPEC-BUDGET-ACC-001**: 设 `llm.max-tokens=64` 后单次输出被截断，且用量记录的 outputTokens 接近上限。
- **SPEC-BUDGET-ACC-002**: 触发一次后台摘要后 `usage-<today>.json` 中 `SUMMARY` token 增长，数值与服务端 usage 一致。
- **SPEC-BUDGET-ACC-003**: `mode=block` 且上限极小时，再次对话返回友好提示，后台 worker 条目保持 PENDING；切回 `off` 后恢复。
- **SPEC-BUDGET-ACC-004**: `mvn` 编译通过。

---

## 11. 规格追溯矩阵

| 规格 ID | 目标文件/组件 | 验证方式 |
|---------|---------------|----------|
| SPEC-BUDGET-GOAL-* | 本文档、整体功能 | 规格审查 |
| SPEC-BUDGET-NON-* | 功能范围 | 代码审查 |
| SPEC-BUDGET-CFG-* | `Config.java`, `application.properties` | 代码审查 |
| SPEC-BUDGET-MTR-* | `UsageMeter`, `SelfAnalystAgent.completePlain`, `OpenAiCompatibleEmbeddingClient` | 单元测试 + 运行验证 |
| SPEC-BUDGET-ENF-* | `UsageMeter`, `SelfAnalystAgent`, `WikiWorker`, `FileIndexWorker`, `DesktopAgentController` | 单元测试 + 运行验证 |
| SPEC-BUDGET-KNOB-* | `SelfAnalystAgent`, `DesktopAgentController` | 代码审查 |
| SPEC-BUDGET-API-* | `/desktop/usage`, `DesktopAgentController`, `ConfigTools` | Controller 测试 |
| SPEC-BUDGET-ACC-* | 全功能链路 | 验收测试 |
