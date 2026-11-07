## Why

用户要求基于最新 main 发布新版本，且必须通过 GitHub 发版工作流打包，不在本地打包。最新预发布为 v0.6.1-beta.10，本次准备 v0.6.1-beta.11，交付其后已合并的 ChatGPT 刷新修复、依赖整合及 AgentScope 2.0.4 取消隔离兼容。

## What Changes

- 将根和六个模块 POM、桌面 package.json、Tauri 配置、两个 Cargo 包及其锁文件共 13 个文件的自身版本同步为 0.6.1-beta.11，不改业务代码或第三方依赖。
- 新增中文发行说明，准确区分已合并修复、本次版本准备验证与尚待执行的精确提交 CI 和 tag 发布验证。
- 保留 ChatGPT 预览资格、真实账号与人工视觉未验、权限保护但未加密的凭据、独立 Embedding 和 schema 5 完整备份/回退警告；说明刷新状态扩展不代表数据库迁移或自动可降级。
- 继续 Pre-release 与非 latest 通道，不取代 v0.6.0 稳定版；通过正常 PR/必需检查/合并后再推新 tag，由远端 Windows Release 构建和验证资产。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

无。本 change 仅准备版本元数据与发行说明，使用 skip_specs: true；windows-release、runtime-storage 与已合并修复的主规格不变。

## Impact

仅 13 个版本文件、docs/releases/v0.6.1-beta.11.md 和本发布规划。不得扩大采集、接触用户真实数据、执行真实 OAuth 登录、修改发布工作流或绕过分支保护。本 change 的完成只表示版本准备完成；正式发版和资产校验由后续发布任务继续执行。其他未完成真实账号或视觉验收的功能 change 保持活动。
