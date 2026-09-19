## Why

单次输出上限增加配置复杂度，不同兼容模型对该参数的处理不同；生成测试固定 16 tokens 还可能在正文出现前耗尽推理预算。用户已明确选择取消客户端输出上限，由模型服务端决定。

## What Changes

- 移除模型设置中的单次最大输出 Tokens 选项及有效配置展示。
- 正常聊天、plain/summary 及连接生成测试均不发送 `max_tokens`、`max_completion_tokens` 或同类输出上限参数。
- 退役 `llm.max-tokens` 与 `LLM_MAX_TOKENS`：已有 TOML 原文保留但不生效，不触发模型重建；模板、支持键、Agent 配置工具不再提供此项。
- **BREAKING** 模型设置 API 不再返回或接受 `maxTokens`；旧客户端显式提交该字段或恢复该字段继承时按不支持字段拒绝。
- 保留每日预算、用量计量、上下文压缩阈值、迭代次数、测试超时、响应大小限制及重复点击保护。
- 同步中英文文案与用户指南，明确省略参数不等于服务端无限输出。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

- `llm-settings`: 移除输出上限字段，生成测试省略输出上限，保留真实生成结果判定及资源限制。
- `llm-budget`: 退役单次输出上限，保留其余预算与结构限制。
- `agent-runtime`: 所有生成调用不设置输出上限。
- `user-configuration`: 输出上限退出有效配置与热更新集合，旧原文兼容但不生效。

## Impact

影响应用模块配置解析、模型创建、模型设置服务及连接探测、桌面表单与文案、HTTP 回归测试、README 两个语言版本及 `docs/llm-settings.md`。不修改供应商配置、不替用户发送付费探测，不改写用户现存 TOML。沿用当前功能分支并保留之前布局与目录修复。
