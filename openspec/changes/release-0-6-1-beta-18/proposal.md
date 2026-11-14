## Why

用户明确授权邮箱目录选择功能实施、PR、合并与发版。本变更准备 v0.6.1-beta.18；功能 PR #154 的最新 head `4810920` 在 run `43600644616` 的五项必需 CI 全部通过后正常合并至 main `3a51619e563d2b9ee9e40b078f0628e19925fa6c`；版本分支已从临时准备起点重定位至该实际合并提交，准备工作不代表已发布。

## What Changes

- 仅同步 13 个项目自身版本文件至 0.6.1-beta.18，并新增中文发行说明。
- 补记 `default-all-mail-folders` 的真实浏览器验收、首次 CI 失败与修复证据；保留 Windows/Tauri 人工与真实邮箱未验收限制，不归档。
- 版本分支已重定位至 PR #154 真实 main 合并 SHA，核对功能内容一致及版本差异后创建独立版本 PR。版本 PR 最新必需 CI 全绿且正常合并后，在精确合并 SHA 建立标签，通过现有远端 Windows Release 工作流构建。
- 保持 beta Pre-release、非 latest。不改业务逻辑、依赖或工作流，不本地打包，不访问真实邮箱或用户电脑。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

无。仅版本元数据、发行说明和验证记录，设置 `skip_specs: true`；沿用 windows-release 主规格，不新增行为契约或架构设计。

## Impact

根与六模块 POM、桌面 package.json、Tauri 配置、桌面与 sidecar Cargo.toml/Cargo.lock、发行说明及本 change/功能 change 的进度记录。其他活动 change 不动、不归档。
