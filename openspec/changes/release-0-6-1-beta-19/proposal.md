## Why

用户已授权本次邮箱与会话输入框改进提 PR、合并并发布。功能 PR #160 已在五项必需检查通过后合并，合并提交为 `6538be12f1a2d3c26ac6ca44d1e25267779ffe79`；本 change 仅准备对应的 beta19 版本元数据及发行说明。

## What Changes

- 将 Maven、桌面端和 accessibility sidecar 共 13 份版本文件统一到 `0.6.1-beta.19`。
- 新增中文发行说明，记录已合并的邮箱全文件夹访问简化、输入框图片预览及验证边界。
- 通过独立版本 PR 等待最新提交的必需检查全部通过后合并，再在精确合并提交创建发布标签，使用远端 Windows 工作流打包。
- 作为 Pre-release 发布，不成为 latest；不本地打包，不修改依赖或发布工作流，不新增业务行为。

## Capabilities

### New Capabilities

无。本 change 设置 `skip_specs: true`，仅更新版本与发行文档。

### Modified Capabilities

无。遵循现有 `windows-release` 规格，功能契约已随 PR #160 同步。

## Impact

根及六个模块 POM，桌面 package.json、Tauri 配置、桌面壳与 sidecar 的 Cargo.toml/Cargo.lock，以及 `docs/releases/v0.6.1-beta.19.md`。不归档其他 change，不修改既有用户数据。
