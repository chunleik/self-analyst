## 1. 发布准备

- [x] 1.1 从最新 origin/main 建立独立发布分支 codex/release-v0.2.6，核对 v0.2.5 之后的提交及远端标签，记录发布基线 SHA：43407f52ad4531294209aa42c7a9343ac832c36f。
- [x] 1.2 将 Maven、桌面及两个 Rust 工程的项目版本和相关锁文件更新为 0.2.6；核对差异中第三方依赖版本不变，运行 mvn --batch-mode validate。
- [x] 1.3 编写 docs/releases/v0.2.6.md，更新 docs/architecture.md 中当前安装包示例；对照已合并 PR 核验更新内容、升级说明和分发文件名。
- [x] 1.4 运行 openspec validate release-v0-2-6 --strict、openspec validate --all --strict 和 git diff --check，确认无行为契约变化后归档发布准备 change；保留原工作区中的无关空提案和输出。

## 2. 归档后的发布交付

沿用 v0.2.5 的准备变更归档方式，下列实际交付证据由 PR、Actions 和 GitHub Release 记录：

1. 推送功能分支并创建 PR，正文关联 release-v0-2-6 和验证结果。
2. 等待 PR 最新提交全部必需检查成功，包括 Windows 全量验证；如同步 main 或新增修复提交，重新等待对应提交的检查。
3. 合并 PR，从合并提交创建并推送 v0.2.6 标签。
4. 等待 Windows Release 成功，确认正式 Release 及便携 ZIP、0.2.6 NSIS 安装包和两份 SHA-256 文件；核验下载摘要并应用中文发布说明。
5. 向用户提供正式 Release 链接和验证结果，仅在工作流及附件核验完成后报告发布成功。

验证记录：Maven validate 通过；OpenSpec 全量严格校验 28 项通过；git diff --check 通过。发布准备无行为契约变化，无需同步主规格。docs/releases/v0.2.6.md 与 docs/architecture.md 作为用户文档保留。
