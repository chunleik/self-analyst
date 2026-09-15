## 1. 发布基线与版本

- [x] 1.1 重新获取 origin/main，核对最新 Release、v0.2.7 标签不存在以及 PR #47、#48 的合并状态，基于 main 建立 codex/release-v0.2.7；记录基线 SHA，保留无关工作区内容。
- [x] 1.2 更新根及子模块 pom.xml、self-analyst-desktop/package.json、src-tauri/tauri.conf.json、两个 Rust 工程 Cargo.toml 和 Cargo.lock 的项目版本为 0.2.7；通过结构化版本读取和 git diff 确认一致且未修改第三方依赖。

## 2. 发布说明

- [x] 2.1 编写 docs/releases/v0.2.7.md，对照 v0.2.6..origin/main、相关主规格及 PR #47/#48 验证记录，说明活动统计、帮助菜单、统计迁移和升级回退注意事项；核实没有声称未验证的行为。
- [x] 2.2 更新 docs/architecture.md 的安装包示例为 0.2.7，检查差异中的文件名与打包规则一致；本次不重复修改已随功能 PR 更新的 README。

## 3. 验证与归档

- [x] 3.1 在干净发布工作区运行 openspec validate release-v0-2-7 --strict、openspec validate --all --strict 和版本一致性检查，记录结果；执行 mvn test 及两个 Rust 工程 cargo test，处理实际失败。
- [x] 3.2 确认准备任务完成、无新增行为契约而无需同步主规格，使用归档技能归档 change；记录 docs/releases/v0.2.7.md 和 docs/architecture.md 作为持续维护文档保留，并再次严格验证。

## 后续发布交付

归档完成后提交并推送功能分支，创建 PR 并注明 change 和验证结果。等待最新提交所有必需检查（含 Windows 全量验证）成功，按本次发布授权合并；从核实的合并提交创建并推送 v0.2.7。等待 Windows Release 成功，更新正式发布正文，确认 ZIP、NSIS 和两份 SHA-256 文件并核验摘要，向用户返回正式 Release 链接。这些外部交付步骤必须完成后才能报告发布成功。
