## 1. 发布准备

- [x] 1.1 更新六个 POM、桌面 package.json/tauri.conf.json、两个 Cargo.toml/Cargo.lock 的项目版本为 0.2.0；通过结构化解析和 Cargo 锁定元数据核对一致性。
- [x] 1.2 编写 docs/releases/v0.2.0.md，核对 v0.1.0..origin/main 提交与配置主规格中的升级映射；更新 docs/architecture.md 的安装包示例。

## 2. 验证与归档

- [x] 2.1 运行 Maven validate、两个 Cargo metadata --locked --offline --no-deps 和 cargo test --locked --offline --manifest-path self-analyst-desktop/src-tauri/Cargo.toml，确认构建元数据与桌面回归通过；核对关于信息继续使用 CARGO_PKG_VERSION。
- [x] 2.2 运行 OpenSpec change 与全量严格校验、git diff --check，核对变更范围后归档；本次不改变行为契约，无主规格同步。

归档后的发布交付通过 GitHub 留存证据：创建 PR，确认最新提交的 Windows 全量验证成功后合并；在合并提交打 v0.2.0 标签，等待 Windows Release 成功，核对安装包、便携包及两份 SHA-256，并使用本次发布说明更新 Release 正文。

验证调整：额外运行的 scripts/check-desktop-tray.ps1 在第 32 行失败，因为仍要求已移除的 SingleInstanceGuard；main 已改为 instance::Instance，相关源码与脚本相对 main 均无本次改动。该脚本不在 CI 或发布门禁中，本次记录既有问题，以当前 Rust 回归及远端全量检查验证发布，不修改旧脚本。

本地结果：六个 Maven 模块 validate 成功；两个 Cargo 锁定离线元数据通过；桌面 Rust 测试 13/13 通过；发布说明 16 行迁移映射与主规格完全一致；OpenSpec change 严格校验及全量 22 项通过；git diff --check 通过。design.md 按项目规则不适用，未创建；无待同步增量规格。
