## Why

用户已授权输入区优化提交 PR、合并并发布新版。功能 PR #155 在五项检查（包括 Windows 全量验证）全部成功后合并为 `2db1d8f3a04c7f5fd57a0932ee8703504ce607a7`；当前最新标签 beta.15，本次准备 v0.6.1-beta.16。

## What Changes

- 仅同步 13 个项目自身版本文件至 0.6.1-beta.16，新增中文发行说明。
- 保持 beta 预发布、非 latest；版本 PR 必需检查全过后正常合并，再向精确合并提交创建标签，通过现有 Windows Release 远端打包。
- 不改业务、依赖或工作流，不本地打包，不连接用户电脑，不包含 PR #154。
- 明确 Windows CI 已通过但真实输入法、系统拖放、视觉与截图未验收；发布产物和工作流需另行核验。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

无。纯版本元数据，使用 `skip_specs: true`，沿用 windows-release 主规格，不需要主规格同步或架构设计。

## Impact

根与六模块 POM、桌面 package.json、Tauri 配置、桌面及 sidecar Cargo.toml/Cargo.lock；发行说明与本 change。其他活动 change 不归档。
