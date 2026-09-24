## 1. 发布准备

- [x] 1.1 从已包含 PR #63 的 `origin/main`（`ed63c6e`）建立 `codex/release-v0-5-0-beta-1`，确认远端没有 `v0.5.0-beta.1` 标签。
- [x] 1.2 将根 POM、五个模块父版本、`self-analyst-desktop/package.json`、`tauri.conf.json`、两个 Cargo 包版本及对应 `Cargo.lock` 中的同名包版本改为 `0.5.0-beta.1`。运行 `./scripts/resolve-release-channel.ps1 -Tag v0.5.0-beta.1`，输出必须是 `prerelease`。
- [x] 1.3 编写 `docs/releases/v0.5.0-beta.1.md`，更新 `docs/architecture.md` 的安装包示例。
- [x] 1.4 运行 `openspec validate release-v0-5-0-beta-1 --strict` 和 `mvn --batch-mode validate`。

## 2. 外部交付

- [ ] 2.1 提交并推送发布分支，创建 PR，关联 `release-v0-5-0-beta-1` 和验证结果。
- [ ] 2.2 等待该 PR 最新提交的必需检查通过，包括 Windows 全量验证，再合并 main。
- [ ] 2.3 在合并提交上创建并推送 `v0.5.0-beta.1`，等待 Windows Release 成功。
- [ ] 2.4 确认 GitHub Release 为 Pre-release，含便携 ZIP、NSIS 安装包和 SHA-256，且最新稳定版仍是 `v0.4.0`。
