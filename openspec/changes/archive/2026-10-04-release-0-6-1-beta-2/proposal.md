## Why

将已通过必需检查并合入 main 的可配置 Neo4j 手工同步交付为 v0.6.1-beta.2，供用户在稳定版之外试用。接续 v0.6.1-beta.1 预发布线，最新正式版保持 v0.6.0，预发布不进入稳定更新通道。

## What Changes

- 以合入 Neo4j 功能 PR #107 的 main 提交 267bc4114496b39b58aba1b5ea4b98d7e65fedce 为准备基线，核对新标签和 Release 尚不存在。
- 将根 POM、六个模块父版本、桌面 package.json、Tauri 配置、两个 Cargo 包清单及自身锁版本统一为 0.6.1-beta.2。
- 新增中文发行说明 docs/releases/v0.6.1-beta.2.md，说明默认关闭、逐次确认的同步行为、数据范围、环境变量凭据、独占命名空间及升级备份风险。
- 发布准备经本地相关验证和 OpenSpec 归档后创建发布 PR；等待最新提交的必需检查成功后按正常保护流程合入 main，再以全新标签触发现有 Windows Release。
- 本 change 只调整发布元数据及说明，不改变已合入功能、依赖、采集范围、数据格式或发布工作流，不连接真实数据库或调用模型。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

无。沿用 windows-release 的 SPEC-REL-002、SPEC-REL-005 和现有 neo4j-sync 等主规格；纯版本与文档调整没有规格级行为变更，设置 skip_specs: true。

## Impact

涉及 13 处项目版本字段和中文发行说明。沿用 GitHub Actions 构建 Windows 便携 ZIP、版本化 NSIS 安装包及两份 SHA-256。Release 标记为 Pre-release 且不替代最新正式版。Neo4j 默认为关闭，发布过程不迁移或发送真实用户数据；实际启用后每次同步仍需用户核对目标与范围并确认。
