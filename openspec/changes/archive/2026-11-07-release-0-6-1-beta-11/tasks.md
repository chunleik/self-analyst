## 1. 版本准备

- [x] 1.1 将指定 13 个文件的自身版本从 0.6.1-beta.10 改为 0.6.1-beta.11，逐文件核对仅一处变化并解析 XML/JSON/TOML；执行 resolve-release-channel.ps1 确认 prerelease。
- [x] 1.2 新增 docs/releases/v0.6.1-beta.11.md；对照 beta.10..main 的四个合并提交核验范围，明确凭据刷新与取消修复、依赖兼容、无新增数据库迁移、备份与未完成的真实账户/视觉验收边界。
- [x] 1.3 运行版本与 UI 相关 Node 测试、OpenSpec 全量 strict、git diff --check；记录真实结果并通过 CLI 归档本纯版本准备 change，不将后续 PR/发布门禁标为已完成。

## 范围与后续发布门禁

本 change 使用 skip_specs: true，不改变行为契约，无需同步主规格；纯版本元数据及用户发行说明不涉及新设计取舍，不创建 design.md。其他活动 change 不归档。

版本准备完成后，发布任务继续提交/推送功能分支、创建 draft PR、等待精确 head 的 Windows 全量验证及全部必需检查、正常合并，复核 tag 未占用后建立 draft prerelease 并推 tag 触发远端 Windows Release。校验四个资产、SHA-256、ZIP/JAR/运行时和安装验证后才公开；保持 Pre-release、非 latest。禁止本地打包、强推、管理员绕过、复用旧提交检查或执行真实账号登录。

## 版本准备验证记录

- 2026-11-07：版本准备基于 main 的 16e332fdbbd07b687f70cc6ef66aa9f8006ce02a。指定 13 个文件逐项与 HEAD 比较，均仅一处 beta.10→beta.11 自身版本替换；XML、JSON、TOML 解析通过，第三方依赖与锁定版本未改。
- 使用既有 PowerShell 执行 scripts/resolve-release-channel.ps1 -Tag v0.6.1-beta.11，输出 prerelease；仅将临时缓存放入 /tmp，未改发布脚本。
- 在仓库根执行 node --test self-analyst-app/src/test/js/resolve-release-channel.test.mjs self-analyst-app/src/test/js/llm-settings.test.mjs self-analyst-app/src/test/js/chatgpt-settings.test.mjs self-analyst-app/src/test/js/chatgpt-runtime-packaging.test.mjs：60 项，37 通过、23 个 Windows 专属条件跳过，0 失败。
- 在仓库根执行 node --test：339 项，307 通过、32 个条件跳过，0 失败。本轮不执行本地 package、便携或 NSIS 构建；精确版本提交的 Java/原生和发布完整验证交由远端必需检查与 tag 工作流，不能用该 Node 结果代替。
- openspec validate --all --strict --no-interactive：归档前 40/40 通过；git diff --check 通过。没有行为规格改动，无需同步主规格，docs/releases/v0.6.1-beta.11.md 作为用户向发行说明保留。
- 发行说明经 beta.10..main 的 4 个提交交叉核对，包含新增刷新锁和待身份校验状态的旧版凭据兼容风险，不宣称可直接降级。真实 OAuth 账户与人工视觉验收仍未完成；相关功能 change 保持活动。

- CLI 已归档本纯版本 change；归档后全量 strict 39/39 通过。独立只读复核完成，并修正文件采集 .gitignore 例外与回退断开/退出的先后说明。
