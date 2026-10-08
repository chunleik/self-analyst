## Why

将已通过必需检查并合入 main 的看板完整概览与已保存主题明细交付为 v0.6.1-beta.4，让用户直接阅读全天概览和各主题叙述。接续 v0.6.1-beta.3 预发布线，最新正式版保持 v0.6.0，预发布不进入稳定更新通道。

## What Changes

- 以 PR #111 合入后的 main 提交 88760ce2f65f082d6718369d7c6ff76b7e48c44a 为准备基线，核对新标签与 Release 尚不存在。
- 将根 POM、六个模块父版本、桌面 package.json、Tauri 配置、两个 Cargo 包清单及自身锁版本统一为 0.6.1-beta.4。
- 新增中文发行说明 docs/releases/v0.6.1-beta.4.md，说明默认展示完整概览、展开后按保存顺序阅读主题标题与叙述、空证据隐藏，以及备份升级注意事项。
- 发布准备通过本地相关验证和 OpenSpec 归档后创建发布 PR；最新提交的必需检查成功后按正常保护流程合入 main，再以全新标签触发现有 Windows Release。
- 本 change 仅调整发布元数据与说明，不修改已合入功能、依赖、数据格式或发布工作流，不调用模型，也不重新生成摘要。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

无。沿用 windows-release 的 SPEC-REL-002、SPEC-REL-005 及已同步的 desktop-summary 主规格。纯版本与发行说明调整不涉及规格级行为变更，设置 skip_specs: true。

## Impact

涉及 13 处项目版本字段和中文发行说明。GitHub Actions 继续构建 Windows 便携 ZIP、版本化 NSIS 安装包及两份 SHA-256。Release 标记为 Pre-release，不替代最新正式版。看板只展示已经保存的概览和主题，不改变采集范围、持久化字段或外发数据。
