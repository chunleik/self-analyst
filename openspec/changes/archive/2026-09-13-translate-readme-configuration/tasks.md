## 1. 文档翻译

- [x] 1.1 将 README.md 配置章节完整译为英文，核对配置语义、代码标识符和链接目标保持一致，中文扫描仅保留语言切换入口。
- [x] 1.2 运行 git diff --check 和 openspec validate translate-readme-configuration --strict，记录验证结果后归档；纯翻译无需同步主规格。

验证结果：git diff --check 与 OpenSpec 严格校验通过；README.md 中文扫描仅剩语言切换入口。配置标识符和链接目标保持不变。纯翻译不需要 design.md、增量规格或主规格同步，docs/ 下现有文档继续保留。
