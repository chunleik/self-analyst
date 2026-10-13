## Why

PR #120 已将摘要对话修复合入 main，需要按用户要求发布下一个 beta 供桌面试用。最高预发布为 v0.6.1-beta.7，最新稳定版仍为 v0.6.0，本次准备不替代稳定版的 v0.6.1-beta.8。

## What Changes

- 基于 PR #120 合并提交 `0adf4f4624c3e4c8e29513d3ec7c77b2c711dcfa`；其树与已通过完整必需检查的 head `b91e69bfd9946d9c586deb3475971ce9ba41d9d4` 一致。确认 beta8 标签和 Release 尚不存在。
- 将 Maven 父子模块、桌面 package.json、Tauri 配置和两个 Cargo 包/锁文件中的 13 处项目版本同步到 `0.6.1-beta.8`。
- 新增中文发行说明，记录已完成的修复流程、本地零模型编辑、用户确认来源、统计/累计周期预算保留、展示层诊断清理及实际 Windows CI/合成截图证据。
- **BREAKING（已合入功能的数据兼容性说明）**：Wiki schema 4→5 不可直接降级，升级前须备份完整运行根及所有外置数据目录。自动 Wiki 备份不能替代整应用备份，无可用旧备份不承诺回退。
- 不新增业务行为、不修改第三方依赖、既有工作流或权限。版本准备 PR 必需检查通过并合入 main 后，按既有 beta 发布通道完成 tag、分发包和校验和发布；公开前保证发行说明含升级警告。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

无。本变更仅调整版本元数据和发行说明，设置 `skip_specs: true`。修复行为及数据边界已由 PR #120 同步的 summary-repair、desktop-chat、desktop-summary、llm-wiki 主规格覆盖；发布沿用 SPEC-REL-002 和 SPEC-REL-005。

## Impact

13 处项目版本及 `docs/releases/v0.6.1-beta.8.md`。预发布维持手动下载，不成为 latest，不替代 v0.6.0；不删除或覆盖旧版本资产。版本准备不在用户数据上执行迁移或调用真实模型。仍披露真实 Windows WebView2 人工验收未完成。
