## Why

用户要求合并当前开放 PR 并发布新 beta。启动时快照仅包含 PR #122 和 #123；最高预发布为 v0.6.1-beta.8，最新稳定版为 v0.6.0，本次准备 v0.6.1-beta.9，保持稳定通道不变。

## What Changes

- 纳入“关于”当前精确版本发布时间（PR #122）与全天阻塞/周期额度耗尽摘要的本地修复入口（PR #123）；两项功能各自通过最新提交的必需检查后合并。
- 同步根与六个模块 POM、desktop package.json、Tauri 配置、两个 Cargo 包及锁文件共 13 处自身版本到 0.6.1-beta.9。
- 新增中文发行说明，记录两 PR 的用户变化、验证证据、实际视觉验收边界及安装/回退说明。
- 本轮没有新增数据库迁移；保留 beta8 已引入 Wiki schema 5 的完整备份与不可无备份降级提醒，明确不把它描述成本版新增迁移。
- 版本准备不改变业务逻辑、第三方依赖、工作流、权限或采集范围。版本 PR 自身必需检查通过后再合并、创建全新 beta tag；既有 tag 工作流质量门禁成功及下载资产校验通过后公开预发布。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

无。版本元数据与发行说明不新增行为契约，设置 skip_specs: true；既有 desktop-help、desktop-summary、summary-repair 与 windows-release 主规格覆盖本轮已实现行为和发布通道。

## Impact

13 处自身版本及 docs/releases/v0.6.1-beta.9.md。预发布不成为 latest，v0.6.0 稳定版不变，不删除或覆盖旧版本资产。无真实用户数据库迁移、收费模型调用或 Windows WebView2 人工验收。About 的真实浏览器视觉验收仍有缺口，不将其 change 误归档为完成。
