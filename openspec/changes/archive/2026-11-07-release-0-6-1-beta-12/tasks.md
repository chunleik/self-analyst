## 1. 版本准备

- [x] 1.1 确认 PR #141 最新 head 的必需检查通过并正常合并，以合并后的 main 为发布基线；记录提交及 CI 链接。
- [x] 1.2 将指定 13 个文件的自身版本从 0.6.1-beta.11 改为 0.6.1-beta.12，逐文件确认只有一处替换并解析 XML/JSON/TOML；执行 resolve-release-channel.ps1 确认 prerelease。
- [x] 1.3 新增 docs/releases/v0.6.1-beta.12.md，核对 beta.11..main 范围；明确 SSE 完成事件要求、模型校验文案、备份和真实账户/视觉验收边界。
- [x] 1.4 运行 Node 定向与完整测试、OpenSpec 全量 strict、git diff --check，记录真实结果并通过 CLI 归档本纯版本准备 change；不将后续 PR/发布门禁写成已完成。

## 范围与后续发布门禁

本 change 使用 skip_specs: true，无行为规格改动，无需同步主规格；纯版本与发行文档没有新的设计取舍，不创建 design.md。其他活动 change 保持原状。

版本准备后继续提交并推送功能分支、创建 draft PR，等待精确最新 head 的 Windows 全量验证与全部必需检查，正常合并。复核新 tag 未占用后建立 draft prerelease，推 tag 触发 GitHub Actions Windows Release；校验四个资产、SHA-256、ZIP/JAR/运行时及远端安装检查后公开。保留 Pre-release、非 latest，禁止本地打包、强推、管理员绕过或复用旧提交检查。

## 版本准备验证记录

- 2026-11-07：PR #141 精确 head 5e4a2e8ca4f95c5287c00f242fc532fb9d40fe38 的 Windows 全量验证和三平台数据守卫全部成功，未提交 approval、未绕过检查。CI：https://github.com/chunleik/self-analyst/actions/runs/42331563439 。正常 squash 合并后 main 基线为 af186a631ce0bb9ec73fe23d179175f8f119d1a5；beta.11..main 仅该 PR。
- 指定 13 个版本文件相对该合并基线均仅有一处 0.6.1-beta.11→0.6.1-beta.12 自身版本替换；XML/JSON/TOML 解析通过，第三方依赖和锁定版本未改。执行原 scripts/resolve-release-channel.ps1 -Tag v0.6.1-beta.12 输出 prerelease。
- 在仓库根运行 release-channel、i18n、i18n-catalog、llm-settings、chatgpt-settings 与 chatgpt-runtime-packaging 六组 Node 测试：68 项，45 通过、23 个平台条件跳过、0 失败。仓库根 node --test：340 项，308 通过、32 个条件跳过、0 失败。
- OpenSpec 归档前全量 strict 40/40 与 git diff --check 通过。本轮不执行本地 package、便携或 NSIS 构建；精确版本 PR 的 Java/原生验证与发布 tag 的 Windows 资产验证继续由远端门禁完成，不能用 Node 结果替代。
- 发行说明经过独立只读核对，明确真实账户只完成最小生成样例，未扩大为登录、刷新、断开、工具续轮或套餐验收；补充恢复旧备份会丢失备份后新增数据和保留当前副本的提示。
- 没有行为规格改动，无需同步主规格；纯版本准备完成后通过 CLI 归档，其他功能 change 保持活动。后续版本 PR、tag 工作流、资产校验和公开状态不在此提前标记成功。
