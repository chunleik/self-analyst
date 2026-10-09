## 1. 发布准备

- [x] 1.1 核对 PR #116 精确 head 的全部必需检查成功并已合并，确认 beta.5 是最高预发布且 beta.6 标签不存在。
- [x] 1.2 同步 13 处项目版本字段并新增中文发行说明；审阅差异确认没有依赖或发布工作流变化。
- [x] 1.3 运行根目录 Node 测试、PowerShell 发布通道核对、OpenSpec 1.11.0 全量严格校验与 git diff --check，并记录环境限制。
- [x] 1.4 确认无规格级行为变化并归档发布准备 change，复验归档后的全量严格校验。

## 2. 归档后的交付

按用户授权提交版本准备 PR，等待最新提交 Windows 全量验证和三平台数据守卫成功后合入 main。推送全新 `v0.6.1-beta.6` 标签触发现有 Windows Release，监测至终态；核验 Pre-release 标记、v0.6.0 稳定版不变、便携 ZIP/NSIS/两份 SHA-256 完整可下载，实际下载计算校验和并检查包内三个菜单入口、原生目标映射与版本。发布页使用本版本中文说明，不声称已完成实机视觉验收。

## 范围与限制

仅发布元数据与说明调整，`skip_specs: true`；无新增架构决策，不创建 design.md。保留 `add-project-website-entry` 和 `show-dashboard-overview` 尚未完成的实机视觉验收项，不将 CI 等同于人工视觉验证。当前环境没有 Maven/Cargo，本地不声明完整 Java/Rust/Windows 验证通过；已合并功能 head 的 CI 已通过，版本准备及发布质量门禁仍需各自验证。


## 验证记录

- PR #116 最终 head `eb5b1b0ceac692a9b964f2cece1701b57671cbc6` 的 Windows 全量验证及三平台数据守卫全部通过；已合并为 `c9a09545e6527ea7c5f9d5bb245ef4f542b09eff`。
- 13 处项目版本均改为 `0.6.1-beta.6`；差异只涉及自身版本和发行说明，没有依赖或工作流变更。
- PowerShell 原始发布通道脚本输出 `prerelease`。
- 根目录 Node 测试：267 项，236 通过、31 平台用例跳过、0 失败。
- OpenSpec 1.11.0 归档前严格校验 35/35 通过；git diff --check 通过。主规格无变化，无需同步。
- 本次本地环境无 Maven/Cargo；功能提交 CI 全通过不替代版本准备 PR 和发布工作流各自的验证，也不代表人工视觉验证。
