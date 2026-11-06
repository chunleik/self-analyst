## Context

Neo4j 5.26 内嵌测试数据库使用 Lucene 9 的 codec；应用搜索独立使用 Lucene 10。PR #131 将 ontology 的三个显式测试依赖也升至 10.6.0，CI 报 `ClassNotFoundException: org.apache.lucene.codecs.lucene99.Lucene99Codec`。主分支保护要求最新 main 与成功的 Windows 全量验证。

## Goals / Non-Goals

**Goals:** 在一个可审查、可回滚的依赖变更中验证六项升级，保留 ontology 测试类路径隔离。

**Non-Goals:** 不迁移 Neo4j 主版本，不变更产品行为，不修改自动合并或 CI 策略，不处理独立的 AgentScope 迁移。

## Decisions

- 直接采用已审查的 Dependabot 锁文件变更与 POM 版本，避免无关依赖更新：#127 Tauri CLI 2.12.4；#128 Logback 1.6.6；#131 应用 Lucene 10.6.0；#132 ureq 3.5.1 / ureq-proto 0.7.1 / webpki-roots 1.0.11；#133 JGit 7.8.0.202609011348-r；#134 tauri-plugin-shell 2.6.0。
- ontology 的 Lucene 三个 test 依赖统一引用独立 `neo4j-lucene.version` 属性，值保持 9.11.1。继续显式覆盖父 POM 的 Lucene 10，而不把应用搜索整体降级。
- 增加快速类路径回归测试，验证 Neo4j 需要的 Lucene major 和 codec；现有数据库集成测试继续验证实际启动和快照写入。
- 使用一个整合 PR 验证升级组合，成功合并后才关闭被覆盖 PR，保留来源链接；不以已有单项 CI 代替整合后的检查。

## Risks / Trade-offs

- [升级之间存在交互] → 在最新 main 上运行完整 CI，并保留必需 Windows 检查和三平台守卫。
- [将来 Neo4j 升级改变 Lucene 需求] → 独立版本属性和带明确错误的回归测试使兼容变更显式可见。
- [原 PR 的历史不直接进入 main] → 在 PR 与本设计逐项列出来源；整合提交可整体回滚。

## CI 时序修复

首次整合 CI 的 Windows 数据守卫在 UI 烟测失败：`open()` 等待 `.summary-repair-card` 存在，但异步加载占位卡也满足条件，立即点击尚未出现的 `Validate locally` 导致竞争。点击 helper 改为沿用 25 秒有界轮询，等待指定按钮存在且启用后点击一次；缺失或一直禁用的按钮仍失败。不改变应用逻辑、CI 工作流、浏览器覆盖或跳过条件。新增独立 Node 回归测试覆盖延迟出现、禁用转启用和永久缺失失败。
