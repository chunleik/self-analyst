## 1. 版本准备

- [x] 1.1 核对启动时 PR #122/#123 快照、最新 head 必需检查与合并树，确认 beta9 尚不存在且 stable v0.6.0 不变。
- [x] 1.2 将 13 处自身版本同步为 0.6.1-beta.9，以原 resolve-release-channel.ps1 和完整 diff 核验无业务、依赖或 workflow 改动。
- [x] 1.3 新增 docs/releases/v0.6.1-beta.9.md，覆盖两 PR、无新增迁移、schema5 既有升级边界与真实视觉验收缺口，并独立只读复核。
- [x] 1.4 运行全量 Maven/Node、OpenSpec 严格检查和差异检查，分别记录通过、跳过、未执行项。
- [x] 1.5 确认无新行为契约无需同步主规格，以 CLI 归档版本准备 change，再次运行 OpenSpec 严格校验。

## 2. 归档后的发布交付

版本准备 change 归档仅表示版本元数据和发行说明已验证，不代替后续 PR 或发布门禁。用户已授权本次合并并发布：推送版本分支、创建 PR；仅在精确 head 的 Windows 全量与三平台数据守卫成功后合并。再核验新标签未占用，以既有 tag 工作流构建；创建含完整警告的 draft Pre-release，下载并校验四件资产、SHA-256、ZIP 布局和内嵌版本后公开。核对非 latest 以及 v0.6.0 稳定版保持不变。后续新开放 PR 不自动纳入本次启动快照。

## 范围说明

skip_specs: true；无新设计决策，按条件规则不创建 design.md。现行双语 README 和其他功能 change 不变；About 的视觉缺口保持未完成，不能借版本发版归档它。所有测试仅用合成数据，不访问真实用户数据库或收费模型。

## 验证记录（2026-10-14）

- 13 处自身版本仅由 beta.8 改为 beta.9；两个 Cargo.lock 不改依赖，业务代码、workflow 和权限均不变。独立只读复核无阻断。
- 原 PowerShell 7.6.6 `scripts/resolve-release-channel.ps1 -Tag v0.6.1-beta.9` 输出 `prerelease`。
- 根 Node：306 项、274 通过、32 平台条件跳过、0 失败；Maven 内 Node：304 项、272 通过、32 平台条件跳过、0 失败。
- JDK21/Maven3.9.11 `mvn --batch-mode test`：7/7 模块 BUILD SUCCESS；Java 1,116 项、1,112 通过、4 既有条件跳过、0 失败或错误。
- 归档前 `openspec validate --all --strict`：37/37；`git diff --check` 通过。
- 本地未重复生成 Rust debug 产物；Windows 专属 Rust/Clippy/UIA/打包仍以各自精确提交的 GitHub 门禁为准。真实用户数据、收费模型和 Windows WebView2 人工验收均未执行。

- 启动快照两项 PR 已按顺序合并：#122 为 `a443b69594da1b9de72f518fe287931b6b14e4ea`，#123 为 `b709395265dc5c22bff3115ba75172500b701d6c`；分别与已通过必需 CI 的 `94ab508`、同步 main 后 `ebc57ca` 树完全一致。#123 的 [CI 38543836439](https://github.com/chunleik/self-analyst/actions/runs/38543836439) 首次 UIA 冷启动返回空，未改代码的失败 job 重跑通过，包括完整门禁；未使用旧 head 结果替代。
- 无新行为契约，skip_specs: true，无需主规格同步；版本说明与现行用户指南保留。版本准备校验完成后使用 CLI 归档；About 和其他既有未完成 change 保持原样。

- CLI 归档完成后全量严格校验 36/36、差异检查通过；实际发布尚待版本 PR 与 tag 门禁。
