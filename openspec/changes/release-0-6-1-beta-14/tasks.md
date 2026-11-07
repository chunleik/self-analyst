## 1. 版本准备

- [x] 1.1 复核 #148 合并提交、最新 beta 与 tag，确认以 main 55762027 为版本基线；只在 codex/release-0-6-1-beta-14 工作。
- [x] 1.2 将指定 13 个版本文件的自身版本从 0.6.1-beta.13 同步为 0.6.1-beta.14，逐文件验证仅一处替换，解析 XML/JSON/TOML，并执行 resolve-release-channel.ps1 -Tag v0.6.1-beta.14 确认 prerelease。
- [x] 1.3 新增 docs/releases/v0.6.1-beta.14.md，核对 beta.13..main 提交范围，记录知识页变化、未完成视觉验收与兼容/备份边界；不改 README 或业务代码。
- [x] 1.4 运行发布通道、国际化及知识页定向 Node 测试和完整 Node 测试、OpenSpec 全量 strict、git diff --check，记录真实结果；只提交指定版本/文档文件，不本地打包、不改 workflow。

## 后续发布门禁

本 change 使用 skip_specs: true，无行为规格变更，不需要同步主规格；纯版本准备没有新设计取舍，按 CLI 的条件规则跳过 design.md。知识页及其他活动 change 保持原状，不自动归档。

版本准备完成后提交并推送功能分支，创建 draft 版本 PR；复核最新 head 的全部必需检查，包括 Windows 全量验证。全部成功后按用户发版授权正常合并，无强推、无 approval、无管理员绕过。

复核 v0.6.1-beta.14 未占用后，创建 draft prerelease，并推 tag 指向合并后的精确版本提交。等待现有 Windows Release 远端质量门禁和打包成功，下载并核对四个资产、SHA-256、ZIP/JAR/Java runtime、远端安装检查记录，再公开 Pre-release 且 latest=false。后续发布门禁状态记录于 PR/发布页，不提前写成已通过。


## 版本准备验证记录

- PR #148 已正常合并，合并提交 55762027bf1cd10122da67e6a891711b7abdb305；精确 head 6880415 的五项 CI 全部成功。准备时最新 beta 为 v0.6.1-beta.13，open PR 列表为空。
- 13 个指定版本文件均仅替换一处自身版本，XML/JSON/TOML 解析通过。原 resolve-release-channel.ps1 对 v0.6.1-beta.14 输出 prerelease。
- 定向 Node：60 项，37 通过、23 项 Windows 条件跳过、0 失败；仓库根 node --test：365 项，333 通过、32 项平台条件跳过、0 失败。平台条件测试由后续 Windows CI 执行。
- OpenSpec 全量 strict：41 项通过；git diff --check 通过。README、业务代码、第三方依赖与 workflow 未改。未进行本地打包或真实账户调用；未归档任何 change。
- 版本 PR、tag 打包、资产校验与公开发布仍是后续门禁，不以本地验证代替精确 head 或 tag 的远端结果。
