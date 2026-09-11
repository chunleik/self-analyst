## 1. 双语入口

- [x] 1.1 将原 README 内容保留为 `README.zh-CN.md`，顶部提供英文入口；对照 Git HEAD 确认中文内容完整保留。
- [x] 1.2 将 `README.md` 翻译为英文并链接中文版，核对所有章节、代码示例和配置表的含义。

## 2. 验证与归档

- [x] 2.1 检查两份 README 的本地链接、示例与配置键一致性，运行 `git diff --check` 和 OpenSpec 严格校验。
- [x] 2.2 记录验证结果后归档 change；不涉及主规格同步。

## 验证结果

- 中文版除语言导航外与 Git HEAD 原 README 一致。
- 两版均包含 9 个章节，6 个代码块的命令一致，20 个模块与配置键一致。
- 已检查 40 个本地链接，全部目标存在。
- `git diff --check` 与 `openspec validate default-english-readme --strict` 通过。
- 纯文档变更，不涉及运行行为；按条件省略 design.md，使用 skip_specs 跳过增量规格，无需主规格同步或应用测试。
