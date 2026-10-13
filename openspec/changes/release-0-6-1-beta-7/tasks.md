## 1. 发布准备

- [x] 1.1 核对 PR #118 已合并为 `0a35211b82d81a8bcb1db56dc28d2edd98114433`，且其树与已通过 Windows 全量验证和三平台数据守卫的 head `18d17ec321d2d3dcd489ead2c6503652888dd717` 一致；确认 v0.6.1-beta.6 是最高预发布、v0.6.0 是最新稳定版，且 v0.6.1-beta.7 标签与同名 Release 不存在。
- [x] 1.2 将根 POM、六个模块父版本、`self-analyst-desktop/package.json`、`self-analyst-desktop/src-tauri/tauri.conf.json`、桌面与 axsidecar 的 Cargo.toml 及各自 Cargo.lock 共 13 处项目版本改为 `0.6.1-beta.7`；审阅差异确认没有依赖或发布工作流变化。
- [x] 1.3 新增 `docs/releases/v0.6.1-beta.7.md`，对照 `0a35211` 与 desktop-summary 主规格核对部分摘要、依赖诊断、仅应用统计、完整结果替换、隐私边界和升级说明。
- [ ] 1.4 运行 `scripts/resolve-release-channel.ps1 -Tag v0.6.1-beta.7` 确认输出为 `prerelease`，并运行根目录 `node --test`、`mvn --batch-mode test`、`openspec validate --all --strict` 与 `git diff --check`；分别记录通过、跳过和环境限制。
- [ ] 1.5 确认无规格级行为变化、无需同步主规格；用 OpenSpec CLI 归档本 change，归档后再次运行全量严格校验与 `git diff --check`。

## 2. 归档后的交付

按用户授权提交版本准备 PR，等待最新提交 Windows 全量验证和三平台数据守卫成功后合入 main。推送全新 `v0.6.1-beta.7` 标签触发现有 Windows Release，监测至终态；核验 Pre-release 标记、v0.6.0 稳定版不变、便携 ZIP/NSIS/两份 SHA-256 完整可下载。发布页使用本版本中文说明，不声称已完成实机视觉验收。本准备变更不创建标签、不合并、不创建 GitHub Release。

## 范围与限制

仅发布元数据与说明调整，`skip_specs: true`；无新增架构决策，不创建 design.md。保留 `add-project-website-entry` 和 `show-dashboard-overview` 尚未完成的任务，不将 CI 等同于人工视觉验证。Linux 上不执行 Windows 全量验证、UIAutomation sidecar 构建、合成 UIA 冒烟，以及按平台跳过的 Node 用例。
