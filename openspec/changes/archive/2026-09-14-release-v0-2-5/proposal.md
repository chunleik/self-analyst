## Why

将 v0.2.4 之后已经合入 main 的桌面体验与长期记忆改进发布给用户，并确保分发包版本一致。

## What Changes

- 统一 Maven、桌面及 Rust 工程和锁文件中的项目版本为 0.2.5，不升级第三方依赖。
- 添加中文发布说明，更新架构文档中的安装包文件名示例。
- 通过功能分支 PR、最新提交的必需检查和标签触发的 Windows Release 工作流交付。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

无。仅更新发布元数据和文档，不修改行为契约，使用 skip_specs。

## Impact

影响项目版本配置、两个 Cargo.lock、docs/releases/v0.2.5.md 和架构文档。现有主规格保持不变。
