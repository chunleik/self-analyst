## Why

试用版和候选正式版需要出现在 GitHub Releases 上供手动下载，同时不能取代当前稳定版，也不能让应用内「检查更新」把它们当成最新版本。现有 Windows 发布工作流对所有 `v*` 标签都创建正式 Release，而且不会按标签改写程序版本；当前项目版本仍是 `0.4.0`。只推一个 `v0.5.0-rc.1` 标签会把预发布变成 latest，并使已安装稳定版的更新检查失败。

## What Changes

- 新增 Windows 发布通道：`vX.Y.Z-beta.N` 与 `vX.Y.Z-rc.N` 在校验通过后创建 GitHub Pre-release，并设置不作为最新发布；`vX.Y.Z` 继续创建正式 Release。
- 无法识别的 `v*` 标签不得创建或更新 Release，工作流以失败结束。手动 `workflow_dispatch` 仍只构建并上传 artifact，不创建 Release。
- 标签触发的发布必须在创建 Release 前确认仓库内项目版本与标签版本一致。工作流不根据标签改写 Maven、桌面或 Rust 版本。
- 已存在的同名预发布在重新上传产物时保持 Pre-release，且不成为最新发布。
- 本变更不把当前版本升到某个 beta 或 rc。版本号同步和打标签属于之后单独的发布变更；`0.5.0-beta.1`、`0.5.0-rc.1`、`0.5.0` 只是通道示例。
- 应用内「检查更新」继续只比较最新稳定版，不提示 Beta 或 RC。该行为已有契约，本变更不修改它。

## Capabilities

### New Capabilities

- `windows-release`: Windows 发布工作流如何区分稳定标签、beta/rc 预发布标签、无法识别的标签，以及手动构建不创建 Release。

### Modified Capabilities

- 无。`desktop-help` 的检查更新仍只使用最新稳定版。

## Impact

- `.github/workflows/windows-release.yml`：按标签选择 `gh release create` 参数；预发布使用 `--prerelease --latest=false`。
- 发布前版本一致性检查及其测试。检查对象包括根 POM 与模块 POM、`self-analyst-desktop/package.json`、`self-analyst-desktop/src-tauri/tauri.conf.json`、两个工程的 `Cargo.toml`。
- `docs/testing.md` 中 Windows release workflow 的现有说明，使其与新通道一致。
- 不修改应用内更新检查实现，不新增第三方依赖，不在本变更中改项目版本或创建 Git 标签。
