## 1. 精简 README

- [x] 1.1 核对 README.md 已移除提案列出的章节，并通过 `git diff -- README.md` 确认采用用户原有删减，没有追加操作或迁移提示。
- [x] 1.2 检查 README.md 中全部本地 Markdown 链接的目标文件存在，并运行 `git diff --check` 确认格式检查通过。

## 2. 验证变更记录

- [x] 2.1 运行 `openspec validate simplify-readme --strict` 和 `openspec validate --all --strict`，确认文档 change 与现行主规格校验通过；本次无行为契约变化，无需同步主规格。
