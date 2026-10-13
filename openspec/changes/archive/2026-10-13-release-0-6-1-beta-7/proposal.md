## Why

PR #118 已合并历史部分摘要与依赖阻塞诊断，需要随后发布新的 beta 供桌面安装验证。当前最高预发布为 v0.6.1-beta.6，最新稳定版仍为 v0.6.0，需要准备下一个不替代稳定版的分发版本。

## What Changes

- 以 main 上 v0.6.1-beta.6 之后的唯一提交 `0a35211b82d81a8bcb1db56dc28d2edd98114433`（PR #118，其树与已通过必需检查的 PR head `18d17ec321d2d3dcd489ead2c6503652888dd717` 一致）为准备基线，确认 `v0.6.1-beta.7` 标签与同名 Release 尚不存在。
- 同步 Maven 父子模块、桌面 package.json、Tauri 配置、两个 Cargo 包与锁文件中的 13 处项目版本为 `0.6.1-beta.7`。
- 新增 `docs/releases/v0.6.1-beta.7.md`，记录部分摘要展示、依赖阻塞诊断、仅应用统计、完整摘要替换，以及相关验证与尚未完成的 Windows 实机视觉验收。
- 不修改业务行为、第三方依赖或既有发布工作流。版本准备 PR 通过必需检查并合入 main 后，才用全新标签沿用 Windows Release 发布预发布包。本准备变更不创建标签、不合并、不创建 GitHub Release。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

无。本变更只调整发布元数据和说明，无规格级行为变化，设置 `skip_specs: true`。部分摘要行为已由 PR #118 同步的 desktop-summary 主规格覆盖。沿用 windows-release 的 SPEC-REL-002 与 SPEC-REL-005。

## Impact

涉及 13 处项目版本与本版本中文发行说明。`v0.6.1-beta.7` 发布为 Pre-release，不成为 latest；最新稳定版维持 v0.6.0，不删除或覆盖旧版本资产，不包含 stable 发布。看板只展示已经保存的子摘要和受限诊断字段，不改变采集范围、持久化格式或外发数据。
