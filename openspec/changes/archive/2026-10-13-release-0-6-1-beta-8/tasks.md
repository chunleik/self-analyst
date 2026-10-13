## 1. 版本准备

- [x] 1.1 核验 PR #120 合并提交与最终已通过 Windows 全量/三平台守卫的 head 树一致，确认 beta7 最高预发布、v0.6.0 最新稳定版且 beta8 尚不存在；记录 commit 与 CI/预演链接。
- [x] 1.2 将根/六个模块 POM、desktop package.json、Tauri 配置、两个 Cargo.toml 和各自 Cargo.lock 共 13 处自身版本改为 0.6.1-beta.8，审阅差异确认无业务、依赖或 workflow 变化。
- [x] 1.3 新增 docs/releases/v0.6.1-beta.8.md；核对功能、合成截图与真实验收限制、schema 5 全量备份/不可无备份降级警告，以及用户确认来源和额度描述。
- [x] 1.4 用原 resolve-release-channel.ps1 核验输出 prerelease，运行 node --test、mvn --batch-mode test、openspec validate --all --strict、git diff --check，并记录实际结果。
- [x] 1.5 确认本变更无新行为契约、无需再同步主规格；使用 CLI 归档版本准备 change，归档后全量严格校验和差异检查通过。

## 2. 归档后的发布交付

按已授权端到端发布范围推送版本准备分支并创建 PR；仅在该 PR 最终 head 的 Windows 全量和三平台数据守卫成功后合入 main。重新核验 beta8 尚不存在，再沿既有 tag 工作流发布；公开前确保发行说明含完整升级警告。核验 Pre-release/非 latest、v0.6.0 稳定版不变、四件资产完整，下载核对 SHA-256、ZIP 布局及内嵌版本/修复代码。此准备 change 归档只表示版本元数据和说明已验证，不代替后续 PR/发布门禁。

## 范围说明

skip_specs: true；不改业务逻辑、第三方依赖或发布工作流，无新设计决策，按 design 的条件规则不创建 design.md。用户指南和既有归档保持不变，add-project-website-entry 与 show-dashboard-overview 未完成任务不动。所有本地/CI 数据为合成数据，不在用户数据上执行迁移或请求收费模型。真实 Windows WebView2 人工验收未完成。

## 验证记录（2026-10-13）

- PR #120 最终 `b91e69bfd9946d9c586deb3475971ce9ba41d9d4` 的 [CI 38379027637](https://github.com/chunleik/self-analyst/actions/runs/38379027637) 必需检查全绿；合并 `0adf4f4624c3e4c8e29513d3ec7c77b2c711dcfa` 与其树一致。原实现 [Windows 预演 38375849500](https://github.com/chunleik/self-analyst/actions/runs/38375849500) 完整成功，11 张合成截图已逐张检查及独立抽查。
- 13 处自身版本一致为 0.6.1-beta.8；独立只读审查无阻断，未改变依赖、workflow、业务代码或运行时设置。
- PowerShell 7.6.6 原 `scripts/resolve-release-channel.ps1 -Tag v0.6.1-beta.8` 输出 `prerelease`。本地只读 HOME 导致首次启动失败，改用本次进程的可写 XDG cache/config/data 目录后成功，没有修改系统权限或脚本。
- 根 `node --test`：297 项，265 通过、32 平台跳过、0 失败；Maven 内 Node：292 项，260 通过、32 平台跳过、0 失败。
- JDK 21/Maven 3.9.11 `mvn --batch-mode test`：7/7 模块 BUILD SUCCESS；Java 1,109 项、1,105 通过、4 环境跳过、0 失败/错误。
- 归档前 OpenSpec 1.11.0 `validate --all --strict`：36/36；`git diff --check` 通过。
- 无规格级行为变化，skip_specs: true；无需主规格同步。发行说明、修复指南、运行数据指南作为现行用户文档保留，既有历史归档与无关 change 不变。
- 本地未运行 Windows 专属 Rust/Clippy/UIA/打包检查，版本 PR 和正式发布必须各自验证；真实用户数据升级、收费模型、用户 Windows WebView2 人工验收均未执行。
- CLI 已归档 `2026-10-13-release-0-6-1-beta-8`；归档后全量严格校验 35/35、差异检查通过。后续版本 PR 和标签发布结果由实际 GitHub 检查/Release 记录提供，不以此归档冒充发版完成。
