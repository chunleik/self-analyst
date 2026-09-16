## Why

将 v0.2.7 之后已合并的桌面更新检查、图标修复和双语入门文档改进发布给用户。沿用既有补丁版本节奏，计划发布 v0.2.8。

## What Changes

- 将 Maven、桌面 Node/Tauri 项目、两个 Rust 工程及锁文件中的项目版本统一为 0.2.8。
- 新增 docs/releases/v0.2.8.md，说明桌面帮助与手动检查更新、图标修复及 README 入门改进。
- 更新 docs/architecture.md 的当前安装包示例，通过功能分支 PR、必需检查和 Windows Release 工作流交付正式分发包。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

无。本 change 仅更新发布元数据和文档，业务行为由已合并功能变更及主规格维护，设置 skip_specs: true。

## Impact

涉及项目版本配置、项目锁文件、发布说明和架构文档。沿用现有 Windows 便携 ZIP、NSIS 安装包和 SHA-256 文件发布流程，不升级第三方依赖。
