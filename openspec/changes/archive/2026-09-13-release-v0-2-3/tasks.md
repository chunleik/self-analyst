## 1. 发布元数据与说明

- [x] 1.1 将根 POM、各 Maven 子模块、桌面 package.json、tauri.conf.json、桌面与边车 Cargo.toml/Cargo.lock 的项目版本统一为 `0.2.3`，运行 Maven validate 并核对所有项目自身版本声明。
- [x] 1.2 新增 `docs/releases/v0.2.3.md` 并更新 `docs/architecture.md` 的安装包示例，对照 `v0.2.2..origin/main` 的已合并提交核验发布内容、升级步骤、数据目录和下载文件说明。

## 2. 发布验证与交付

- [x] 2.1 运行 `openspec validate --all --strict`、`git diff --check`、JDK 21 Maven 测试以及两个 Rust 工程的 test/clippy，确认版本变更不改变行为契约且无需同步主规格。
- [x] 2.2 记录发布前验证结果，核对差异只包含 `v0.2.3` 版本元数据、发布说明、架构示例和本 change，并确认没有包含 `hold/refine-chat-memory-experience-2026-09-13` 中的候选方案。

验证记录：Maven validate 及 JDK 21 全量测试成功；桌面 Rust 22 项测试通过，accessibility sidecar 测试通过，两个 Rust 工程 clippy 均无警告；OpenSpec 严格校验 26 项通过；`git diff --check` 通过。版本差异仅覆盖项目自身声明，Cargo 第三方依赖未改变。`design.md` 按条件跳过，无增量规格，主规格无需同步。

归档后的交付证据记录在 PR 和 GitHub Release：推送 `codex/release-v0-2-3` 并创建 PR，等待最新提交包括 Windows 全量验证在内的必需检查全部成功后合并；再从最新 `main` 创建 `v0.2.3` 标签，等待 Windows Release 成功并核对便携 ZIP、NSIS 安装包和两份 `.sha256` 资产。
