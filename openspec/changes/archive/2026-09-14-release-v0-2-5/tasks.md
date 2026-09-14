## 1. 发布准备

- [x] 1.1 更新 Maven、桌面及两个 Rust 工程版本和锁文件为 0.2.5，核对第三方依赖不变并运行 Maven validate。
- [x] 1.2 编写 docs/releases/v0.2.5.md 并更新架构安装包示例，对照 v0.2.4 之后已合并记录核验。
- [x] 1.3 运行 OpenSpec 严格校验和 git diff --check，确认无行为契约变化后归档准备变更。

验证记录：Maven validate、当前 change 严格校验、主规格严格校验和 git diff --check 通过。全量本地校验仅受无关、未跟踪的 add-python-release-assistant 空提案影响，该提案不纳入发布提交；远端干净检出继续执行全量门禁。

## 2. 归档后的发布交付

交付证据由 PR、Actions 和 GitHub Release 记录：功能分支推送并创建 PR，等待最新提交的 Windows 全量验证成功后合并；从合并提交创建 v0.2.5 标签，等待 Windows Release 成功，核对 ZIP、NSIS 安装包和两份校验文件，并使用中文发布说明。
