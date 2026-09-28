## 1. 发布准备

- [x] 1.1 从最新 `origin/main` 建立 `codex/release-v0-5-0-beta-2`，确认远端没有 `v0.5.0-beta.2` 标签。验证：`git tag -l v0.5.0-beta.2` 为空。
- [x] 1.2 将根 POM、五个模块父版本、`self-analyst-desktop/package.json`、`tauri.conf.json`、两个 Cargo 包版本及对应 `Cargo.lock` 中的同名包版本改为 `0.5.0-beta.2`。验证：`./scripts/resolve-release-channel.ps1 -Tag v0.5.0-beta.2` 输出 `prerelease`。
- [x] 1.3 编写 `docs/releases/v0.5.0-beta.2.md`（相对 beta.1：摘要聚焦、上层抽象、隐私排除/`url_host`、旧摘要本地脱敏），更新 `docs/architecture.md` 安装包示例。验证：文件存在且安装包文件名含 `0.5.0-beta.2`。
- [x] 1.4 运行 `openspec validate release-v0-5-0-beta-2 --strict` 和 `mvn --batch-mode validate`。验证：二者成功。

## 2. 外部交付

- [x] 2.1 提交并推送发布分支，创建 PR，关联 `release-v0-5-0-beta-2` 和验证结果。验证：PR URL 可访问。
- [ ] 2.2 等待该 PR 最新提交的必需检查通过，包括 Windows 全量验证，再合并 main。验证：合并后 `main` 含版本提交。
- [ ] 2.3 在合并提交上创建并推送 `v0.5.0-beta.2`，等待 Windows Release 成功。验证：Actions Release 工作流成功。
- [ ] 2.4 确认 GitHub Release 为 Pre-release，含便携 ZIP、NSIS 安装包和 SHA-256，且最新稳定版仍是 `v0.4.0`。验证：`gh release view v0.5.0-beta.2` 与 `gh release view v0.4.0`。
