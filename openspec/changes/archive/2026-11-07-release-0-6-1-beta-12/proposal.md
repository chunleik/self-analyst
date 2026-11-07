## Why

用户要求合并 PR #141 后发版，并通过 GitHub 发版触发打包、不在本地打包。最新预发布为 v0.6.1-beta.11，本次准备 v0.6.1-beta.12，交付 ChatGPT 有效 SSE 响应缺少 Content-Type 时的协议兼容修复，以及模型设置校验错误的中英文文案。

## What Changes

- PR #141 经最新提交的全部必需检查通过并正常合并后，将根和六个模块 POM、桌面 package.json、Tauri 配置、两个 Cargo 包及锁文件共 13 个文件的自身版本同步为 0.6.1-beta.12。
- 新增中文发行说明，准确区分协议兼容、文案修复、已有最小真实账户生成验证与尚未完成的完整端到端和人工视觉验收。
- 仅准备版本元数据和发行文档，不修改业务代码、第三方依赖、采集边界、数据格式或发布工作流。
- 继续使用 Pre-release、非 latest 通道；版本 PR 必需检查通过并合并后，推送新 tag，由 GitHub Actions 构建、验证并上传 Windows 资产，校验后公开。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

无。本 change 使用 skip_specs: true，仅准备版本元数据和发行说明，沿用 windows-release 与 runtime-storage 主规格，不改变行为契约。

## Impact

仅 13 个版本文件、docs/releases/v0.6.1-beta.12.md 和本发布规划。保留其他活动 change 与未完成验收状态，不执行真实账户登录或读取用户凭据。版本准备完成后归档本 change，后续 PR、tag 及资产发布门禁仍由发布任务继续执行。
