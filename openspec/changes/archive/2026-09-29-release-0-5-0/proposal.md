## Why

发布 SelfAnalyst 0.5.0 正式版。当前 main 的项目版本仍为 0.5.0-beta.2，必须先统一版本，才能通过既有发布标签校验。

## What Changes

- 将 Maven 根版本与模块父版本、桌面 package.json、Tauri 配置、桌面壳及 sidecar 的 Cargo 清单和锁文件版本统一为 0.5.0。
- 按现行流程通过发布 PR、远端必需检查和合并后，以 v0.5.0 标签触发 Windows Release 工作流。
- 本变更仅调整发布元数据，不改变功能、依赖或发布行为契约。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

无。沿用 windows-release 现行规格，设置 skip_specs: true。

## Impact

涉及各模块版本清单和 Cargo 锁文件；正式产物为 Windows 便携 ZIP、NSIS 安装包及校验和。用户操作方式不变，无需修改 README 或主规格。
