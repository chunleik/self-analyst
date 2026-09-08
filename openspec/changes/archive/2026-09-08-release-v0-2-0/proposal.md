## Why

主分支已完成自启动、模型配置热更新与事件配置改名，需要以 v0.2.0 交付，并确保安装包、桌面关于信息及后端模块使用一致版本。

## What Changes

- 将 Maven 父子模块、桌面 Node/Tauri/Rust 与 accessibility sidecar 的项目版本统一为 0.2.0，同步两个 Cargo 锁文件中的本项目条目。
- 新增简体中文发布说明，概述 v0.1.0 以来的变化，明确已合入的 **BREAKING** 事件配置改名及手动升级步骤。
- 更新架构文档中的安装包版本示例；沿用现有 Windows CI、标签发布和分发文件命名。
- 通过功能分支 PR 交付；最新提交必需检查成功后合并，再在 main 的发布提交创建 v0.2.0 标签，验证工作流生成的 Release 与四个分发附件。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

无。本次仅更新发布元数据和文档，现有行为已由相关主规格定义，使用 `skip_specs: true`。没有新增架构决策，不创建 design.md。

## Impact

涉及六个 POM、桌面 package.json、tauri.conf.json、两个 Cargo.toml 与 Cargo.lock、docs/architecture.md 和 docs/releases/v0.2.0.md。不修改依赖版本、业务逻辑或发布工作流。现有主规格无需同步；docs/ 下用户指南及历史资料保留，新发布说明作为版本升级入口。
