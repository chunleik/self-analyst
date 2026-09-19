## Context

动机见 proposal.md。普通模型在创建 chat/plain 的 GenerateOptions 时按 LlmSettings 添加 maxTokens；探测另行固定 max_tokens=16。输出上限还出现在 Config、支持键、环境映射、有效配置、Agent 工具和模型设置 API 中，因此只删除表单不足以实现用户选择。

## Goals / Non-Goals

**Goals:** 从配置到序列化请求完整移除输出上限，升级后旧键不恢复限制，保持草稿、凭据和预算行为。

**Non-Goals:** 不承诺无限输出，不放宽测试成功判定，不修改每日预算、输入压缩、超时、响应大小、生成轮数，不自动向用户模型发送测试。

## Decisions

- 从 Config 与 LlmSettings 移除输出上限成员，从 GenerateOptions 和探测 JSON 去掉对应字段；用本地 HTTP 服务器捕获真实 SDK 请求，确认默认序列化没有补入 max_tokens 或 max_completion_tokens。
- 将 llm.max-tokens 纳入 DeprecatedKeys，删除支持键及环境变量映射。合法 TOML 中的旧值按退役键忽略并保留原文，不因旧值为负值或非数字而阻止启动；TOML 语法、结构的通用安全检查仍保留。
- 模型设置 API 移除 maxTokens 的读取、更新和恢复继承支持，旧客户端显式提交按 unsupported_field/invalid_request 拒绝，不伪装保存成功。Agent 配置工具不再列出或修改该键。
- 测试保留固定短提示、非流式、15 秒总等待、1 MiB 响应限制、无重定向及重复提交保护；文案说明不设置客户端输出上限，实际消耗由模型服务决定。
- 现有文件不自动迁移，不删除用户旧配置行；配置热更新比较只包括仍有效的四个模型连接参数。

## Risks / Trade-offs

- [SDK 默认输出参数与预期不同] → 断言实际 HTTP 请求字段缺失，覆盖对话、plain/summary 和探测。
- [旧键残留继续触发校验或模型切换] → 测试启动、raw 保存、有效配置与候选资源创建次数。
- [测试生成消耗增加] → 保留固定短提示、超时和响应大小限制，明确计费提醒而不宣称最小消耗。
- [已有布局改动被覆盖] → 在当前实现上移除单个字段，保留三个页签、固定操作栏与目录打开修复。

## Migration Plan

更新程序即可停用旧配置；不改写用户文件。执行针对性 Java/Node 测试、跨模块 Maven 全量测试与 OpenSpec 严格校验，同步四份主规格和中英文文档后归档。重新打包并重启当前项目展示结果。
