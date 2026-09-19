## Why

将 v0.2.8 之后已合并的合并事件存储（含不兼容的存储格式迁移与 raw 能力退役）及构建脚本修复发布给用户。因包含不兼容行为变更，参照 v0.2.0 处理不兼容配置改名的先例升级次版本号，计划发布 v0.3.0。

## What Changes

- 将 Maven、桌面 Node/Tauri 项目、两个 Rust 工程及锁文件中的项目版本统一为 0.3.0。
- 新增 docs/releases/v0.3.0.md，说明合并事件存储、既有数据迁移与备份清理、`events.raw.*` 配置退役及从 v0.2.8 升级的步骤。
- 更新 docs/architecture.md 的当前安装包示例为 0.3.0。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

无。本 change 仅更新发布元数据和文档，业务行为由已合并功能变更（`2026-09-17-adopt-merged-event-storage`、`2026-09-17-fix-app-jar-resolution`）及主规格维护，设置 skip_specs: true。

## Impact

涉及项目版本配置、项目锁文件、发布说明和架构文档。沿用现有 Windows 便携 ZIP、NSIS 安装包和 SHA-256 文件发布流程，不升级第三方依赖。
