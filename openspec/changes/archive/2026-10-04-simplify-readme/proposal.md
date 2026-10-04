## Why

`README.md`（365 行）与 `README.zh-CN.md`（286 行）在多次功能迭代中不断追加段落，混入了大量 Wiki 预算、重试节奏、检查点和迁移等实现细节，并存在重复内容（托盘行为、帮助菜单、长期记忆各出现两次）。首页难以快速回答“这是什么、如何开始、数据去哪里”，且两个语言版本的章节顺序已不一致。

## What Changes

- 将两份 README 重组为相同的章节结构：简介、快速开始、主要功能、隐私与数据边界、配置、开发、文档、贡献、许可证。
- 删除重复段落，把帮助与更新、界面语言、长期记忆、个人知识、活动统计口径收敛为“主要功能”中的单条说明，并链接到 `docs/` 下的对应指南。
- 移除 Wiki 摘要预算、重试退避、检查点、Token 记账和事件库迁移等实现细节；这些内容以 `openspec/specs/` 主规格和 `docs/architecture.md`、`docs/activity-statistics.md`、`docs/runtime-storage.md` 为准，README 仅保留链接。
- 配置表精简为常用键（模型连接、事件服务模式与端口、隐私排除列表），完整键表链接到用户配置规格。
- 保留仅在 README 中有用户向说明的 `wiki.privacy.excludeApps` / `wiki.privacy.excludeSites` 用法。
- 移除会随发布过期的“当前正式版本为 0.6.0”表述，改为链接 Releases 与发布说明。
- 本变更为纯文档整理，不新增、修改或移除任何产品行为。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

无。本变更不改变任何规格级行为，`.openspec.yaml` 设置 `skip_specs: true`。`desktop-help` 规格要求使用指南指向对应语言的 README，两个文件的路径与语言均保持不变。

## Impact

- 文件：`README.md`（英文）、`README.zh-CN.md`（简体中文）。
- 测试：`self-analyst-app/src/test/js/static-config.test.mjs` 断言 `README.md` 包含 `llm.base-url`、`llm.model` 配置表行和 `Node.js 20+`，整理后须继续满足。
- 不涉及代码、API、依赖或主规格。
