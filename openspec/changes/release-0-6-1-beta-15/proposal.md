## Why

用户要求为新增 MCP 与 QQ/163 只读邮箱创建 PR、合并并发版。功能 PR #151 已合并至 main f8c09caac0f8f29ef09b0cddf1076c65ed670d78，最新预发布为 v0.6.1-beta.14，本次准备 v0.6.1-beta.15，继续保持预发布、非 latest 通道。

## What Changes

- 以 #151 合并提交为基线，只调整 13 个项目自身版本文件到 0.6.1-beta.15。
- 新增简体中文发行说明，交代 MCP/QQ/163 能力、旧搜索安全收紧、模型目标批准、邮件留存、凭据与备份回退边界。
- 记录功能 PR 精确 head 的五项 CI 与 Windows 原生凭据/JAR 服务加载实际结果；保留真实邮箱、Tauri 端到端与视觉未验收，以及版本 PR、发布 tag、便携/安装包验证待完成的限制。
- 不改业务代码、第三方依赖或工作流，不本地打包，不连接用户电脑，不使用真实邮箱凭据。
- 版本 PR 最新提交全部必需检查通过后方可正常合并；之后先建 draft prerelease，再推精确合并提交的 tag，等待现有 GitHub Actions 远端打包及独立资产核验完成后公开，保持 latest=false。
- 本次本地工作仅准备并提交版本文件与文档，推送、PR 和发版由后续授权执行阶段推进；保留所有其他活动 change，不自动归档。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

无。使用 `skip_specs: true`；本次仅准备版本元数据和发行说明，沿用 windows-release、runtime-storage、mcp-integrations 与 mail-integration 等主规格，不改变行为契约。

## Impact

根与六个模块 POM、桌面 package.json、Tauri 配置、两个 Cargo 包及其自身锁文件版本，共 13 个文件；另新增 docs/releases/v0.6.1-beta.15.md 和本变更规划。版本准备不更改功能 PR 的实现、已同步规格或原有验收记录，所有发布打包在 GitHub Actions 执行。
