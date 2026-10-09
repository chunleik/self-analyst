## Why

PR #116 已合并帮助菜单的官网、帮助文档和个人知识指南入口，用户要求随后发布新的 beta 供桌面安装验证。当前最高预发布为 v0.6.1-beta.5，需要准备下一个不替代稳定版的分发版本。

## What Changes

- 同步 Maven 父子模块、桌面 package.json、Tauri 配置、两个 Cargo 包与锁文件中的 13 处项目版本为 `0.6.1-beta.6`。
- 新增 `docs/releases/v0.6.1-beta.6.md`，记录三个帮助入口、安全系统浏览器打开、相关验证与尚未完成的 Windows 实机视觉验收。
- 不修改业务行为、第三方依赖或既有发布工作流；以独立版本准备 PR 通过必需检查后创建新标签，沿用 Windows Release 发布预发布包。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

无。本变更只调整发布元数据和说明，无规格级行为变化，设置 `skip_specs: true`。帮助行为已由 PR #116 的 desktop-help 主规格覆盖。

## Impact

涉及 13 处项目版本与本版本中文发行说明。`v0.6.1-beta.6` 发布为 Pre-release，不成为 latest；最新稳定版维持 v0.6.0，不删除或覆盖旧版本资产，不包含 stable 发布。
