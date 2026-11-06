## 1. 整合与兼容修复

- [x] 1.1 整合 #127/#128/#131/#132/#133/#134 的 POM 与锁文件差异，审查最终 diff 与来源精确版本一致。
- [x] 1.2 在 ontology POM 独立管理 Lucene 9.11.1，并新增 Neo4jLuceneCompatibilityTest，执行定向 Maven 测试验证 codec 与数据库兼容。

- [x] 1.3 修复 `scripts/check-summary-repair-ui.mjs` 的按钮加载竞争，并通过 Node 回归测试验证等待、单次点击和有界失败。

## 2. 验证与交付

- [x] 2.1 执行 OpenSpec 严格校验、差异检查及适用测试，记录成功、失败或环境限制。
- [ ] 2.2 在整合 PR 最新提交运行 Windows 全量验证和三平台数据守卫并确认成功，不使用旧提交检查替代。
- [ ] 2.3 确认无行为规格变化后通过 CLI 归档 change，检查最终提交并保留 PR 来源及验证证据。

验证记录：`openspec validate --all --strict` 40 项通过；Node UI 测试 296 通过、32 跳过；`git diff --check` 通过。定向 Neo4j 测试 6/6 通过；全量 Maven Java 测试 1178 项、0 失败、0 错误、7 跳过，Maven 中 Node 测试 300 通过、32 跳过。此环境未安装 Cargo，Rust 验证交由既有远端 Windows CI；未执行本地打包。

时序修复验证：新增 2 项 Node 回归测试通过；修复后显式 Node 套件 298 通过、32 跳过；OpenSpec 40/40 通过。远端 Windows 浏览器验证仍必须通过后才能合并。
