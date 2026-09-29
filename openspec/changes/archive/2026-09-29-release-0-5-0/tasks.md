## 1. 正式版元数据

- [x] 1.1 将根及五个模块 POM、桌面 package.json、tauri.conf.json、两个 Cargo.toml 及对应 Cargo.lock 的项目版本统一为 0.5.0，通过 scripts/resolve-release-channel.ps1 -Tag v0.5.0 验证。

## 2. 验证与归档

- [x] 2.1 运行发布通道现有回归测试、openspec validate release-0-5-0 --strict 和 openspec validate --all --strict，检查 git diff --check 与版本差异。
- [x] 2.2 确认无行为契约变更、无需主规格或用户文档同步，并归档 change；交付 PR 中记录验证结果，合并须等待最新提交的 Windows 全量验证等必需检查通过。

## 验证记录

- 版本通道检查返回 stable，现有发布通道测试 22/22 通过，git diff --check 通过。
- 当前 change 严格校验通过；仅含 Git 跟踪规格及本 change 的临时目录全量严格校验 31/31 通过。原工作区另有未被 Git 跟踪的 add-python-release-assistant 空目录导致全量校验失败，保留该目录，不纳入发布。
- 无主规格增量，无需同步；docs/architecture.md 中 beta.2 包名示例及 docs/releases/ 历史发行说明保持原样。
- PR 与标签发布在归档后的交付阶段执行，远端 CI 和 Windows Release 结果以 GitHub 记录为准。
