## 1. 文档整理

- [x] 1.1 重写 `README.md`（英文），按统一章节结构精简，保留 `llm.base-url`、`llm.model` 配置表行与 `Node.js 20+`
- [x] 1.2 重写 `README.zh-CN.md`（简体中文），与英文版章节和信息逐项对应
- [x] 1.3 检查完整差异：英文版不含中文说明（语言入口与 `简体中文` 链接除外），中文版无遗漏；两份 README 的相对链接目标均存在

## 2. 验证

- [x] 2.1 运行 `node self-analyst-app/src/test/js/static-config.test.mjs`，通过
- [x] 2.2 运行 `openspec validate simplify-readme --strict`，通过
