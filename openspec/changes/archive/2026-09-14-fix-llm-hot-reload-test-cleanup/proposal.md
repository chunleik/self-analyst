## Why

PR #38 的 Windows 全量验证在 `LlmHotReloadIntegrationTest` 结束后清理 JUnit 临时目录时失败，日志报告 `DirectoryNotEmptyException`。测试夹具创建的 `UsageMeter` 使用后台线程写盘，却未在关闭时调用 `flush()`，存在测试结束后继续创建用量目录的竞争，需要完善测试资源生命周期。

## What Changes

- 修复热更新集成测试夹具的清理：在 Agent 工作收敛后刷新并停止夹具拥有的用量计量器，确保其他测试资源也能释放。
- 增加能够验证夹具关闭时用量持久化和后台执行器终止的回归测试，避免仅依赖偶发的目录删除异常。
- 在 PR #38 的现有功能分支交付，保留 README 翻译；同步最新 main 后重新运行验证。
- 本变更仅修复测试设施，不增加功能，不改变模型热更新或预算行为契约，也不是文档基线迁移。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

无。现行 `SPEC-AGT-LIVE-003` 与 `SPEC-BUDGET-MTR-001..003` 保持不变；本变更设置 `skip_specs: true`。

## Impact

- 主要文件：`self-analyst-app/src/test/java/com/selfanalyst/agent/LlmHotReloadIntegrationTest.java`。
- 验证：目标集成测试、用量相关测试、本地完整测试和 PR 最新提交的 GitHub Actions，包含 Windows 全量验证。
- 不改变生产资源所有权、公共 API、依赖或用户配置；无需修改主规格及用户文档。
