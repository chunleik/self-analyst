## 1. 整合与兼容修复

- [x] 1.1 整合 #127/#128/#131/#132/#133/#134 的 POM 与锁文件差异，审查最终 diff 与来源精确版本一致。
- [ ] 1.2 在 ontology POM 独立管理 Lucene 9.11.1，并新增 Neo4jLuceneCompatibilityTest，执行定向 Maven 测试验证 codec 与数据库兼容。

## 2. 验证与交付

- [x] 2.1 执行 OpenSpec 严格校验、差异检查及适用测试，记录成功、失败或环境限制。
- [ ] 2.2 在整合 PR 最新提交运行 Windows 全量验证和三平台数据守卫并确认成功，不使用旧提交检查替代。
- [ ] 2.3 确认无行为规格变化后通过 CLI 归档 change，检查最终提交并保留 PR 来源及验证证据。

验证记录：`openspec validate --all --strict` 40 项通过；Node UI 测试 296 通过、32 跳过；`git diff --check` 通过。定向 Maven 测试使用现有 Java 21 编译包装器运行中。此环境未安装 Cargo，Rust 验证交由既有远端 Windows CI；未执行本地打包。
