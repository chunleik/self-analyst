## 1. 更新仓库指南文档

- [ ] 1.1 在 `AGENTS.md` 的“构建、测试与开发命令”及“测试规范”章节中明确记录：本地验证时仅在浏览器端验证，不做本地桌面程序打包，实际的桌面程序打包放到 GitHub 执行；通过检查 `AGENTS.md` 变更内容确认表述准确、位置清晰。

## 2. 校验与收尾

- [ ] 2.1 运行 `openspec validate document-local-browser-verification --strict`，确认 OpenSpec 变更严格校验通过且无额外非预期文件改动。
