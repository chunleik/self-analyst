## Why

将 v0.6.0 之后已合入 main 的知识页样式改进、桌面文档校验兼容修复和依赖更新交付为 v0.6.1-beta.1，供用户在稳定版之外试用。现有最新正式版保持 v0.6.0，预发布不会进入稳定更新通道。

## What Changes

- 以最新 main（准备时为 f5b7918893ce3243e2bb3f6048f4ea5a13baef59）为发布基线；核对 v0.6.1-beta.1 标签和 Release 尚不存在。
- 将根 POM、六个模块父版本、桌面 package.json、Tauri 配置及两个 Cargo 包清单和自身锁版本统一为 0.6.1-beta.1。
- 新增中文发行说明 docs/releases/v0.6.1-beta.1.md，说明已有变更、试用风险、备份升级和 Windows 分发文件。
- 发布准备通过本地相关验证并归档后，创建发布 PR；等待最新提交的必需检查成功后按正常保护流程合入 main，再以 v0.6.1-beta.1 标签触发 Windows Release。
- 本 change 仅调整发布元数据及说明，不新增功能、依赖或发布工作流行为，不触发付费模型分析。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

无。沿用 windows-release 的 SPEC-REL-002 与 SPEC-REL-005 等现行契约。版本更新和发行说明没有规格级行为变更，在 .openspec.yaml 中设置 skip_specs: true。

## Impact

涉及 13 处项目版本字段与一份中文发行说明。沿用 GitHub Actions 发布 Windows 10/11 便携 ZIP、版本化 NSIS 安装包和各自 SHA-256；Release 标为 Pre-release 且不替换最新正式版。不调整数据格式、采集边界、模型调用或安装方式。
