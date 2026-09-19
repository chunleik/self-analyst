## 1. 发布准备

- [x] 1.1 从包含 fix-app-jar-resolution 的最新 main 建立 codex/release-v0.3.0，核对 v0.2.8 之后的提交及远端标签，记录发布基线 SHA。
- [x] 1.2 将所有项目版本和对应锁文件更新为 0.3.0；核对差异中第三方依赖版本不变，运行 Maven validate。
- [x] 1.3 编写 docs/releases/v0.3.0.md（合并事件存储、迁移与备份清理、raw 配置退役、升级步骤），更新 docs/architecture.md 当前安装包示例。
- [x] 1.4 运行 OpenSpec 严格校验、版本一致性检查和 git diff --check，确认无行为契约变化。

## 2. 外部交付

- [ ] 2.1 提交并推送发布分支，创建 PR，关联 release-v0-3-0 和验证结果。
- [ ] 2.2 等待 PR 最新提交的全部必需检查成功，包括 Windows 全量验证，合并 main。
- [ ] 2.3 从核实的合并提交创建并推送 v0.3.0 标签，等待 Windows Release 成功。
- [ ] 2.4 核验正式 Release、便携 ZIP、NSIS 安装包及两份 SHA-256 文件和下载摘要。
