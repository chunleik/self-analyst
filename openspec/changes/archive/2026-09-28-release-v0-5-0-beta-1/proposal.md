## Why

v0.4.0 之后已合并摘要主题覆盖、可恢复的摘要合成，以及桌面端移除合并事件状态。这些是向后兼容的功能变化，按语义版本进入下一个次版本的试用通道。预发布工作流已在 main 上，但项目版本仍是 0.4.0，直接打 `v0.5.0-beta.1` 会被版本核对拒绝。

## What Changes

- 将 Maven 父子模块、桌面 `package.json`、Tauri 配置、两个 Cargo 包及对应锁文件中的项目版本统一为 `0.5.0-beta.1`。
- 新增 `docs/releases/v0.5.0-beta.1.md`，说明相对 v0.4.0 的试用内容，并标明应用内检查更新不会提示本版本。
- 将 `docs/architecture.md` 的当前安装包示例改为 `0.5.0-beta.1`。
- 本变更不修改业务行为，不升级第三方依赖。正式版 `0.5.0` 和 rc 不在本次发布。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

无。摘要与桌面行为已由已合并变更维护。本变更只更新发布版本和说明，设置 `skip_specs: true`。

## Impact

涉及项目版本、两份 `Cargo.lock`、发布说明和架构文档中的安装包文件名。推送 `v0.5.0-beta.1` 后由 Windows Release 创建 Pre-release，不成为最新稳定版。
