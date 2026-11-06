## Why

现有依赖升级 PR 需要在同一最新主分支上完成兼容性验证。Lucene 升级错误地覆盖 Neo4j 内嵌测试数据库所需的 Lucene 9，导致 CI 无法启动数据库；逐个合并其余升级还会反复使分支落后。

## What Changes

- 整合 PR #127、#128、#131、#132、#133、#134 的依赖升级，逐项保留来源和精确版本。
- 应用 Lucene 升至 10.6.0，但 ontology 测试继续隔离使用 9.11.1，并补充类路径兼容性回归测试。
- 保留现有 Windows 必需验证和三平台数据守卫，不变更分支保护或自动合并策略。

## Capabilities

### New Capabilities

无。此变更仅维护依赖与测试，不新增用户行为。

### Modified Capabilities

无。现有搜索、Neo4j 同步和桌面壳行为契约保持不变，设置 `skip_specs: true`，不更改主规格。

## Impact

影响父 POM、ontology 测试 POM/测试代码、桌面 pnpm/Cargo 锁文件。AgentScope 分组 PR #126 独立处理；已合并的 Log4j PR #129 保持不变。仅在新 PR 最新提交的必需检查成功后合并并关闭被完整替代的 PR。
