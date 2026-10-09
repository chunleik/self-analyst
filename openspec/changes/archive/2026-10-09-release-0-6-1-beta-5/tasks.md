## 1. 发布准备

- [x] 1.1 核对 PR #114 精确 head 的全部必需检查成功并已合并；beta.4 是最高预发布，beta.5 标签不存在。
- [x] 1.2 同步 13 处项目版本字段，新增中文发行说明；完整审阅差异，不改依赖或工作流。
- [x] 1.3 运行 Node 测试、PowerShell 发布通道检查、OpenSpec 1.11.0 全量严格校验和 git diff --check；记录环境限制。
- [x] 1.4 确认无规格级行为变化并归档发布准备 change，复验归档后的全量严格校验。

## 2. 归档后的交付

按用户授权提交发布准备 PR，最新提交 Windows 全量验证与三平台数据守卫成功后合入 main；推送全新 v0.6.1-beta.5 标签触发现有 Windows Release，监测至终态并验证 Release 为 Pre-release、稳定版不变、便携 ZIP/NSIS/两份 SHA-256 资产完整可下载。发布页使用本版本中文说明。远端验证以对应 PR、Actions 和 Release 为准。

## 范围与限制

只调整版本与说明，无规格级行为变化，skip_specs 为 true；纯发布元数据调整不需要 design.md。保留既有 show-dashboard-overview change，其真实浏览器视觉验收仍未完成，不因此声明视觉通过。当前未安装 Maven，完整 Java/Rust/Windows 冒烟由最新提交 CI 和发布质量门禁执行。

## 验证记录

- 13 处项目自身版本均更新为 0.6.1-beta.5，逐文件差异只改项目版本，没有依赖或发布工作流变化。
- 使用现有 PowerShell 工具并将缓存配置到可写临时目录，运行原始 scripts/resolve-release-channel.ps1 -Tag v0.6.1-beta.5，输出 prerelease。
- 根目录 node --test：262 项，231 通过、31 平台用例跳过、0 失败；当前没有 Maven，不声明本地 Java/Rust/Windows 全量验证通过。
- 与 CI 一致的 OpenSpec 1.11.0 归档前全量严格校验 34/34 通过，git diff --check 通过。
- 仅新增本版本中文说明，保留历史发行说明与未完成视觉验收记录；无需同步主规格。
- 发布准备 change 已通过 CLI 归档；归档后 OpenSpec 1.11.0 全量严格校验 33/33 通过。
