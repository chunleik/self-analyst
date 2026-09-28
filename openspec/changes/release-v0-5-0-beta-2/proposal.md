## Why

v0.5.0-beta.1 之后已合并摘要聚焦、上层摘要抽象、隐私排除与旧摘要本地脱敏等试用改进。需要切出下一预发布包，让试用用户手动下载验证，同时不取代 v0.4.0 稳定版。

## What Changes

- 将 Maven 父子模块、桌面 `package.json`、Tauri 配置、两个 Cargo 包及对应锁文件中的项目版本统一为 `0.5.0-beta.2`。
- 新增 `docs/releases/v0.5.0-beta.2.md`，说明相对 `v0.5.0-beta.1` 的试用内容，并标明应用内检查更新不会提示本版本。
- 将 `docs/architecture.md` 的当前安装包示例改为 `0.5.0-beta.2`。
- 本变更不修改业务行为，不升级第三方依赖。正式版 `0.5.0` 和 rc 不在本次发布。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

无。摘要隐私与抽象行为已由已合并变更及主规格维护。本变更只更新发布版本和说明，设置 `skip_specs: true`。

## Impact

涉及项目版本、两份 `Cargo.lock`、发布说明和架构文档中的安装包文件名。推送 `v0.5.0-beta.2` 后由 Windows Release 创建 Pre-release，不成为最新稳定版；最新稳定版仍为 `v0.4.0`。
