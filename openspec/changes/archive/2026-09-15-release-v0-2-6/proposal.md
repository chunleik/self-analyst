## Why

将 v0.2.5 之后已合入 main 的图片输入、HTML/SVG 输出和桌面修复发布给用户，统一分发包版本与发布说明。沿用现有补丁版本发布惯例，本次版本定为 v0.2.6。

## What Changes

- 将 Maven、桌面及两个 Rust 工程的项目版本和相关锁文件统一为 0.2.6。
- 新增中文发布说明 docs/releases/v0.2.6.md，汇总已合并 PR #42、#43、#44、#45，并核实最终 main 基线。
- 更新架构文档中的当前安装包示例，通过功能分支 PR、必需检查和 Windows Release 工作流发布。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

无。本 change 仅维护发布元数据和文档，既有功能契约由各自已归档 change 维护，设置 skip_specs: true。

## Impact

涉及项目版本配置、相关锁文件、docs/releases/v0.2.6.md 和架构文档。通过现有工作流产生 Windows 便携 ZIP、NSIS 安装包及对应 SHA-256 文件；本 change 不引入额外第三方依赖升级。
