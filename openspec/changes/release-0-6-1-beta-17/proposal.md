## Why

用户已再次明确授权继续 PR #157、必需检查通过后合并并发版。功能 PR #157 在精确 head `2c90ab10ae1ef532861a77c23f6ae627a772e73f` 的五项 CI 全部成功后合并为 `c4ab210c48e985bfbcfeba85fed92da9b6f19270`；当前最新预发布 beta.16，本次准备 v0.6.1-beta.17。

## What Changes

- 仅同步 13 个项目自身版本文件至 0.6.1-beta.17，新增中文发行说明。
- 补记既有 `add-connection-deletion-and-tool-dialog` 的最终 CI、截图复核和合并事实；未完成的真实服务、原生人工及后台摘要/压缩与 UI 联合验收继续保留，不归档。
- 保持 beta 预发布、非 latest；版本 PR 最新必需检查全过后正常合并，再向精确合并提交创建标签，通过现有 Windows Release 远端构建。
- 不改业务、依赖或工作流，不本地打包，不连接用户电脑，不包含 PR #154。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

无。仅版本元数据、发行说明和已验证进度记录，使用 `skip_specs: true`。沿用 windows-release 主规格，不新增行为契约或架构设计。

## Impact

根与六模块 POM、桌面 package.json、Tauri 配置、桌面及 sidecar Cargo.toml/Cargo.lock；发行说明、本 change 与功能 change 的任务进度文档。其他活动 change 不动、不归档。
