## Why

用户要求合并知识页优化 PR #148 后发版。该 PR 已正常合并，最新预发布为 v0.6.1-beta.13，本次准备 v0.6.1-beta.14 交付知识页 UX 优化，保持预发布、非 latest 通道。

## What Changes

- 以 #148 合并后的 main 55762027 为基线，只调整 13 个项目自身版本文件到 0.6.1-beta.14。
- 新增中文发行说明，介绍菜单、即时搜索、待整理与时间筛选、关系分组/状态、键盘组合框，并明确截图和 Tauri 人工验收的既有环境限制。
- 保留 beta.13 的隐私、数据兼容、备份与 ChatGPT 预览边界；不修改业务代码、第三方依赖、接口、数据格式或工作流。
- 版本 PR 最新提交的全部 CI 通过后正常合并，再创建 draft prerelease 并推送新 tag，使用现有 GitHub Actions 远端打包。四个发布资产与校验和核对通过后才公开，不本地打包。
- 保留 `improve-knowledge-tab-ux` 等活动 change，不把合并或发版授权视作该 change 的归档确认。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

无。使用 `skip_specs: true`；本次仅准备版本元数据和发行说明，沿用 personal-ontology、windows-release 与 runtime-storage 主规格，不改变行为契约。

## Impact

根与六个模块 POM、桌面 package.json、Tauri 配置、两个 Cargo 包与自身锁文件版本，共 13 个文件；另新增 docs/releases/v0.6.1-beta.14.md 和本变更规划。所有打包在 GitHub Actions 执行，不连接用户电脑，不运行真实账户登录，不修改工作流，不归档其他 change。
