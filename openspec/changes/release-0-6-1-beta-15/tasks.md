## 1. 版本准备

- [x] 1.1 复核 #151 精确 head 与合并提交、最新 beta/tag，以 main f8c09caac0f8f29ef09b0cddf1076c65ed670d78 创建独立 codex/release-0-6-1-beta-15 工作区，并记录远端基线。
- [x] 1.2 将指定 13 个版本文件的自身版本从 0.6.1-beta.14 同步为 0.6.1-beta.15，逐文件验证仅一处替换，解析 XML/JSON/TOML，并执行 resolve-release-channel.ps1 -Tag v0.6.1-beta.15 确认 prerelease。
- [x] 1.3 新增 docs/releases/v0.6.1-beta.15.md，核对 beta.14..main 范围，说明 MCP/QQ/163、旧搜索安全收紧、留存与回退边界；记录 #151 的实际 Windows 结果，明确真实邮箱、Tauri 视觉和发布产物待验收，不改 README 或业务代码。
- [x] 1.4 运行发布通道、国际化、连接与确认卡定向 Node 测试、完整 Node 测试、OpenSpec 1.11.0 全量 strict 和 git diff --check；仅提交指定版本、说明及本 change 文件，不本地打包、不改 workflow、不推送或发版。

## 后续发布门禁

本 change 使用 skip_specs: true，没有行为规格变更，不需要同步主规格；纯版本准备没有新增架构、依赖或迁移设计取舍，按 CLI 的条件规则不创建 design.md。所有其他活动 change 保持原状，不自动归档。

本地版本提交交付后，由后续执行阶段推送分支并创建 draft 版本 PR，复核最新 head 的全部必需检查，包括 Windows 全量验证；全部成功后才正常合并，不强推、不自行 approval、不管理员绕过。

再次确认 v0.6.1-beta.15 未被占用后，先创建 draft prerelease，再推 tag 到版本 PR 的精确合并提交。等待现有 Windows Release 远端门禁和打包成功，独立下载核对四个资产、SHA-256、ZIP/JAR/Java runtime、IMAPS/MIME 服务加载和远端安装检查，再公开 Pre-release 且 latest=false。后续结果记录于版本 PR/发布页，不提前写为已通过。


## 版本准备验证记录

- 2026-11-09 通过 GitHub API 复核 #151 已正常合并，合并提交 f8c09caac0f8f29ef09b0cddf1076c65ed670d78；精确 head 843fb5298d1c38a777fd6c3033c3423563c737cf 的五项 CI 全部成功，运行 https://github.com/chunleik/self-analyst/actions/runs/42606077559。准备时最新预发布为 beta.14，稳定版仍为 v0.6.0，beta.15 tag 尚不存在。
- 13 个指定文件各仅替换一处自身版本，XML/JSON/TOML 解析及包名定位通过。原 resolve-release-channel.ps1 对 v0.6.1-beta.15 输出 prerelease；PowerShell 使用临时可写缓存，不改脚本。
- 定向 Node：57 项，34 通过、23 项 Windows 条件跳过、0 失败；仓库根 node --test：391 项，359 通过、32 项平台/opt-in 跳过、0 失败。Windows 跳过项留待版本 PR 的精确 head CI 验证。
- OpenSpec 1.11.0 全量 strict：44 项通过；git diff --check 通过。发行说明包含功能 CI 的 Windows 凭据/JAR 实测、legacy_default 兼容修复与旧搜索安全收紧，保留真实邮箱、Tauri 视觉及最终分发包未验收边界。
- 变更仅包含 13 个自身版本文件、发行说明及本 change 三个规划文件。README、业务实现、依赖和 workflow 均未改；未本地打包、未使用真实邮箱或用户电脑、未归档其他 change。版本 PR、tag 打包和公开发布是后续门禁，不以本地验证代替。
