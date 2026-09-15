## 1. 文档优化与双语同步

- [x] 1.1 优化 README.zh-CN.md 的简介、快速开始、配置和功能入口；对照 title-capture、llm-settings 主规格与 PRIVACY.md 确认表述准确。
- [x] 1.2 对应修改 README.md，核对章节顺序、配置表与信息一致，保留各语言截图，英文正文不混入中文说明。

## 2. 验证与归档

- [x] 2.1 检查两份 README 的本地链接、完整差异、语言与配置表，运行 git diff --check 和 openspec validate refine-readme-onboarding --strict，并记录结果。


## 验证结果

- 两份 README 各 29 个本地链接均存在；13 个二级章节逐项对应。
- 模块标识、配置键、环境变量和代码格式默认值一致；英文正文无中文说明（语言切换入口除外）。
- 已人工检查完整双语差异，保留各语言截图；git diff --check 与 OpenSpec 严格校验通过。
- 纯文档调整，按规则省略 design.md，未创建增量规格，无需同步主规格或运行业务测试。
- docs/llm-settings.md、docs/runtime-storage.md、docs/activity-statistics.md、docs/README.md 和历史移除说明等现有文档保持原位，继续通过 README 链接访问。
