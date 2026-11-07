## 1. 版本准备

- [x] 1.1 确认当前所有功能 PR 已合并，核对 #146 最新 head 的五项 CI 成功，以合并后的 main e6d0e24 为基线，重新查询 open PR 为零。
- [x] 1.2 将指定 13 个文件的自身版本从 0.6.1-beta.12 改为 0.6.1-beta.13，逐文件确认只有一处替换并解析 XML/JSON/TOML；执行 resolve-release-channel.ps1 确认 prerelease。
- [x] 1.3 新增 docs/releases/v0.6.1-beta.13.md，核对 beta.12..main 范围；明确隐私边界、连续性与 ChatGPT 续聊修复、备份和未完成验收。
- [x] 1.4 运行 Node 定向与完整测试、OpenSpec 全量 strict、git diff --check，记录真实结果并通过 CLI 归档本纯版本准备 change；不将后续 PR/发布门禁写成已完成。

## 范围与后续发布门禁

本 change 使用 skip_specs: true，无行为规格改动，无需同步主规格；纯版本与发行文档没有新的设计取舍，不创建 design.md。其他活动 change 保持原状。

版本准备后提交并推送功能分支、创建 draft PR，等待精确最新 head 的 Windows 全量验证与全部必需检查，正常合并。复核新 tag 未占用后建立 draft prerelease，推 tag 触发 GitHub Actions Windows Release；校验四个资产、SHA-256、ZIP/JAR/运行时及远端安装检查后公开。保留 Pre-release、非 latest，禁止本地打包、强推、管理员绕过或复用旧提交检查。

## 版本准备验证记录

- 2026-11-07：PR #146 精确 head 06d75b311b320c7dfaa0f6674759446e5c91c7b2 的 Windows 全量验证、三平台数据守卫与 Linux 隐私界面验证全部成功；CI：https://github.com/chunleik/self-analyst/actions/runs/42364628267 。正常合并后 main 基线为 e6d0e24d91b6623a1855609a20db25724f9821a2，open PR 查询为空。未提交 approval 或绕过保护。
- 指定 13 个版本文件相对该合并基线均仅有一处 0.6.1-beta.12→0.6.1-beta.13 自身版本替换；XML/JSON/TOML 解析通过，第三方依赖和锁定版本未改。执行原 scripts/resolve-release-channel.ps1 -Tag v0.6.1-beta.13 输出 prerelease；PowerShell 缓存写入临时目录。
- 在仓库根运行 release-channel、i18n、i18n-catalog、llm-settings、chatgpt-settings、chatgpt-runtime-packaging、privacy-settings 七组 Node 测试：78 项，55 通过、23 个平台条件跳过、0 失败。仓库根 node --test：350 项，318 通过、32 个条件跳过、0 失败。首次错误地把目录作为 Node 测试文件参数，未加载测试；改用仓库根标准入口后完成上述全量验证。
- OpenSpec 归档前全量 strict 40/40 与 git diff --check 通过。本轮不执行本地 package、便携或 NSIS 构建；精确版本 PR 的 Java/原生验证与发布 tag 的 Windows 资产验证继续由远端门禁完成。
- 发行说明保留 AQL 聚合与元数据可能透露隐藏活动的边界，不把旧摘要/会话或已发请求写成可追溯撤回；已区分 beta.12 的 SSE 兼容与本次续聊修复。
- 没有行为规格改动，无需同步主规格；纯版本准备完成后通过 CLI 归档。其他功能 change 保持活动，后续版本 PR、tag 工作流、资产校验和公开状态不在此提前标记成功。
