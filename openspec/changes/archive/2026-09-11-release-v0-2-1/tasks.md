## 1. 发布准备

- [x] 1.1 更新六个 POM、桌面 package.json/tauri.conf.json、两个 Cargo.toml/Cargo.lock 的项目版本为 0.2.1，通过结构化解析与 Cargo 锁定元数据核对一致性。
- [x] 1.2 编写 docs/releases/v0.2.1.md，核对 v0.2.0..origin/main 提交与国际化主规格；更新 docs/architecture.md 中的安装包示例。

## 2. 验证与归档

- [x] 2.1 运行 Maven validate 和两个 Cargo metadata --locked --offline --no-deps，核对关于信息继续使用 CARGO_PKG_VERSION。
- [x] 2.2 运行 OpenSpec change 与全量严格校验、git diff --check，核对范围后归档；本次无行为契约变化，无主规格同步。

归档后通过 GitHub 留存发布交付证据：创建 PR，等待最新提交的 Windows 全量验证等必需检查成功后合并，在 main 合并提交创建 v0.2.1 标签，等待 Windows Release 成功，核对便携包、安装包与两份 SHA-256，并以 docs/releases/v0.2.1.md 更新 Release 正文。

本地验证：六个 Maven 模块 validate 成功；两个 Rust 项目的锁定离线元数据均为 0.2.1；Maven/JSON 版本结构化核对通过，关于信息仍使用 CARGO_PKG_VERSION；OpenSpec change 严格校验及全量 22 项通过；git diff --check 通过。无待同步增量规格，design.md 按条件不适用。远端必需检查与发布产物验证在归档后执行。
