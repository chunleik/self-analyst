## Why

将已通过必需检查并合入 main 的 Neo4j 同步配置页调整交付为 v0.6.1-beta.3，让用户直接试用新的入口及面板切换保护。接续 v0.6.1-beta.2 预发布线，最新正式版保持 v0.6.0，预发布不进入稳定更新通道。

## What Changes

- 以 PR #109 合入后的 main 提交 655fb07d18c9c75e1ac0c62e2bd4f808c8d2701c 为准备基线，核对新标签与 Release 尚不存在。
- 将根 POM、六个模块父版本、桌面 package.json、Tauri 配置、两个 Cargo 包清单及自身锁版本统一为 0.6.1-beta.3。
- 修正 Neo4j 指南引言遗漏的旧入口文案，使其与已合入功能和操作步骤一致。
- 新增中文发行说明 docs/releases/v0.6.1-beta.3.md，说明「配置 → Neo4j 同步」入口、逐次确认、异步切换保护以及备份升级与隐私注意事项。
- 发布准备通过本地相关验证和 OpenSpec 归档后创建发布 PR；最新提交的必需检查成功后按正常保护流程合入 main，再以全新标签触发现有 Windows Release。
- 本 change 仅调整发布元数据与说明，不修改已合入功能、依赖、数据格式或发布工作流，不连接真实用户数据库或调用模型。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

无。沿用 windows-release 的 SPEC-REL-002、SPEC-REL-005 及现有 Neo4j 同步与配置页主规格。纯版本与发行说明调整不涉及规格级行为变更，设置 skip_specs: true。

## Impact

涉及 13 处项目版本字段和中文发行说明。GitHub Actions 继续构建 Windows 便携 ZIP、版本化 NSIS 安装包及两份 SHA-256。Release 标记为 Pre-release，不替代最新正式版。Neo4j 仍默认关闭且每次发送前需要用户核对并确认；发布过程不迁移或发送真实用户数据。
