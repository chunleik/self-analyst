## Why

发布包含个人本体／知识功能的 SelfAnalyst 0.6.0 正式版。先确认本体功能 PR 最新提交的必需检查成功并合入 main，再将预发布版本统一为正式版本，满足现行发布标签与项目版本一致性要求。

## What Changes

- 将发布基线设为已合入个人本体功能的最新 main；既有本体实现、主规格与用户文档由对应功能 change 提供。
- 将全部 Maven 项目版本、桌面 package.json、Tauri 配置、两个 Cargo 清单和自身包锁版本统一为 0.6.0。
- 更新双语 README 的版本说明、架构包名示例，并新增中文正式发行说明。
- 通过发布 PR 最新提交的必需检查后合入 main，以 v0.6.0 标签触发 Windows Release，核对正式版标记、Latest、安装包、便携包与校验和。
- 本 change 只维护发布元数据和说明，不新增功能、依赖或行为契约。

## Capabilities

### New Capabilities

无。个人本体能力已由 add-personal-ontology-v06 定义并实施。

### Modified Capabilities

无。沿用 windows-release 与 personal-ontology 的现行契约，设置 skip_specs: true。

## Impact

涉及版本清单、Cargo 锁文件、README.md、README.zh-CN.md、docs/architecture.md 与 docs/releases/v0.6.0.md。沿用 GitHub Actions 发布 Windows 10/11 便携 ZIP、NSIS 安装包及 SHA-256，不调整采集、存储、迁移或发布工作流行为。
