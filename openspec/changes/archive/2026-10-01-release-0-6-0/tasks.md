## 1. 发布基线

- [x] 1.1 核对个人本体 PR #76 最新提交的必需检查（含 Windows 全量验证）成功，按授权合入 main；以 GitHub PR 状态和合并 SHA 为证据，从该 main 创建 codex/release-0.6.0 分支。

## 2. 正式版本准备

- [x] 2.1 将根及各模块 POM、桌面 package.json、tauri.conf.json、两个 Cargo.toml 及自身包 Cargo.lock 版本统一为 0.6.0；运行 scripts/resolve-release-channel.ps1 -Tag v0.6.0，结果必须为 stable。
- [x] 2.2 更新双语 README 版本说明、docs/architecture.md 包名示例，新增 docs/releases/v0.6.0.md；检查完整差异及英文首页无新增中文说明，中文说明与之对应。

## 3. 验证与归档

- [x] 3.1 运行发布通道现有 Node 回归测试、openspec validate release-0-6-0 --strict、跟踪规格全量严格校验及 git diff --check；记录结果，保留无关 add-python-release-assistant 空 change。
- [x] 3.2 确认本体主规格和指南已随功能 PR 同步，本发布无新增契约、无需增量同步；完成任务后使用 openspec-archive-change 归档，并在交付 PR 中记录验证结果。

## 4. 归档后的交付

提交并推送发布功能分支，创建 PR。只有发布 PR 最新提交的必需检查（含 Windows 全量验证）成功、分支满足同步要求后才合入 main。将 v0.6.0 标签指向合并后的 main 提交并推送，由 Windows Release 执行完整质量门禁和分发构建。最终核对工作流成功、标签 SHA、正式版和 Latest 标记、便携 ZIP、NSIS 安装包及对应 SHA-256，并使用本次中文发行说明更新发布页。远端交付证据以 GitHub PR、Actions 和 Release 为准。

## 验证记录

- 个人本体 PR #76 已合入，最新 head 58f34c21106e788ed2ad535745038d45b8327448 的 Windows 全量验证与三平台数据守卫均通过（CI run 36841539733）。发布分支基于 main 合并提交 7820b22d65b5e54aa5742a1821465fe3468d1dc1。
- scripts/resolve-release-channel.ps1 -Tag v0.6.0 返回 stable，发布通道 Node 回归测试 23/23 通过，git diff --check 通过。
- 当前 change 严格校验通过；包含全部 Git 跟踪主规格及本 change 的临时发布快照全量严格校验 32/32 通过。原工作区 add-python-release-assistant 空 change 造成直接全量校验 32 成功、1 失败，保留该无关目录，不纳入提交。
- 完整 README 差异已检查，英文首页新增内容为英文，中文版对应更新。个人本体主规格、中文指南和架构说明已随 #76 合入；本发布无增量规格，不需同步。设计文档因仅调整版本元数据与说明而按条件省略。
- docs/releases/0.6.0-beta.1.md 及其他历史发行文档保留；新增 docs/releases/v0.6.0.md 作为正式版用户说明。远端 PR 检查、标签和 Release 验证在归档后的交付阶段完成，以 GitHub 记录为证据。