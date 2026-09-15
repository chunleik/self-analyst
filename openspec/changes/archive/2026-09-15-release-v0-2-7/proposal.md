## Why

将 v0.2.6 之后已合并的活动统计修复与帮助菜单发布给用户。沿用既有补丁版本节奏，计划发布 v0.2.7；执行前再次确认该版本未被占用。

## What Changes

- 将 Maven 各模块、桌面 Node 项目、两个 Rust 工程及对应锁文件中的项目版本统一为 0.2.7。
- 新增 docs/releases/v0.2.7.md，说明 PR #47 的 AFK 排除与凌晨四点统计日，以及 PR #48 的帮助菜单和导航调整。
- 更新 docs/architecture.md 的当前安装包示例，通过功能分支 PR、必需检查和 Windows Release 工作流交付正式分发包。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

无。本 change 仅更新发布元数据和文档，业务行为由已合并功能变更及主规格维护，设置 skip_specs: true。

## Impact

涉及项目版本配置、项目锁文件条目、发布说明和架构文档。沿用现有 Windows 便携 ZIP、NSIS 安装包和 SHA-256 文件发布流程，不额外升级依赖。发布说明须核实并说明 PR #47 已引入的统计数据迁移及兼容性影响。
