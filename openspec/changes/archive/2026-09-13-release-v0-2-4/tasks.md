## 1. 发布准备

- [x] 1.1 更新 Maven、桌面及 Rust 项目版本和锁文件为 0.2.4，核对第三方依赖不变并运行 Maven validate。
- [x] 1.2 编写 docs/releases/v0.2.4.md，更新架构安装包示例及工作流的免安装数据目录说明，对照已合并修复及主规格核验。
- [x] 1.3 运行桌面 Rust 测试、Clippy、OpenSpec 严格校验及 git diff --check，记录发布准备验证后归档。

## 2. 交付流程

归档后的交付证据由 PR 与 GitHub Release 记录：推送功能分支，等待最新提交的 Windows 全量验证通过后合并；从合并提交创建 v0.2.4 标签，等待 Windows Release 成功，核对 ZIP、NSIS 安装包和两份校验文件，并使用本次中文发布说明。

本变更不改变行为，跳过增量规格；无需技术设计，按条件跳过 design.md。既有 docs 历史文档保持不变。

验证记录：Maven validate 成功；桌面 Rust 25 项测试通过；Clippy 无警告；OpenSpec 严格校验 26 项通过；git diff --check 通过。两个 Cargo 锁文件仅改变项目自身版本，第三方依赖保持不变。主规格无需同步。
