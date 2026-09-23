## 1. 发布通道脚本

- [x] 1.1 新增 `scripts/resolve-release-channel.ps1`：把 `vX.Y.Z` 判为 `stable`，把 `vX.Y.Z-beta.N` 与 `vX.Y.Z-rc.N` 判为 `prerelease`，其他 `v*` 以非零退出码失败；数值段不允许前导零，预发布序号从 1 开始。比对去掉 `v` 后的版本与根 POM 项目版本、五个模块 POM 父版本、`self-analyst-desktop/package.json`、`tauri.conf.json`、两个 Cargo 包版本及对应 `Cargo.lock` 同名包版本。运行 `node --test self-analyst-app/src/test/js/resolve-release-channel.test.mjs`，确认稳定、beta、rc 通过，且 `v01.2.3`、`v0.5.0-beta.0`、`v0.5.0-beta.01`、`v0.5.0-alpha.1`、缺段标签和任一版本字段不一致均失败。

## 2. 发布工作流

- [x] 2.1 修改 `.github/workflows/windows-release.yml`：当引用是 `refs/tags/v*` 时，在质量门禁之前调用 1.1 的脚本；无法识别或版本不一致时立即失败。稳定通道创建 Release 时不添加 `--prerelease`；预发布通道使用 `--prerelease --latest=false`。同名 Release 已存在时继续 `gh release upload --clobber`，预发布通道在上传前执行 `gh release edit <tag> --prerelease --latest=false`。`workflow_dispatch` 且引用不是受支持标签时不创建 Release。对照 `windows-release.yml` 确认上述分支，并再次运行 `node --test self-analyst-app/src/test/js/resolve-release-channel.test.mjs`。

## 3. 文档与校验

- [x] 3.1 更新 `docs/testing.md` 的 SPEC-ITEST-011，写明稳定标签创建正式 Release、beta/rc 标签创建且不成为最新发布的 Pre-release、无法识别的 `v*` 标签失败、手动运行分支只上传 artifact。运行 `openspec validate support-windows-prerelease --strict` 并通过。
