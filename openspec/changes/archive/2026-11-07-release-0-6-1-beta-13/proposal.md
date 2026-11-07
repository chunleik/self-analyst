## Why

用户要求合并当前项目全部 PR 后发一个版本，并通过 GitHub 发版触发打包，不在本地打包。当前全部功能 PR 已合并，最新预发布为 v0.6.1-beta.12，本次准备 v0.6.1-beta.13，交付应用隐私设置、ChatGPT 续聊修复及短暂无前台窗口的采集连续性修复。

## What Changes

- 以 PR #146 合并后的 main e6d0e24 为基线；#143/#144、#145、#146 均已合并，版本准备前 open PR 列表为空。
- 将根和六个模块 POM、桌面 package.json、Tauri 配置、两个 Cargo 包及锁文件共 13 个文件的自身版本同步为 0.6.1-beta.13。
- 新增中文发行说明，准确区分隐私控制、AQL 数值聚合的推断边界、连续性修复、ChatGPT 续聊修复及尚未完成的端到端验收。
- 仅准备版本元数据和发行文档，不修改业务代码、第三方依赖、数据格式或发布工作流。
- 继续使用 Pre-release、非 latest 通道；版本 PR 必需检查通过并合并后，推送新 tag，由 GitHub Actions 构建、验证并上传 Windows 资产，校验后公开。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

无。本 change 使用 skip_specs: true，仅准备版本元数据和发行说明，沿用 windows-release 与 runtime-storage 主规格，不改变行为契约。

## Impact

仅 13 个版本文件、docs/releases/v0.6.1-beta.13.md 和本发布规划。保留其他活动 change 与未完成验收状态，不执行真实账户登录或读取用户凭据。版本准备完成后归档本 change，后续 PR、tag 及资产发布门禁仍由发布任务继续执行。
