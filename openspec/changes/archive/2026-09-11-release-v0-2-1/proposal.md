## Why

主分支已完善中英文覆盖、语言扩展机制和双语 README，需要作为 v0.2.1 发布，确保各构建入口与桌面关于信息的项目版本一致。

## What Changes

- 将六个 Maven POM、桌面 package.json/tauri.conf.json、桌面及边车 Cargo.toml/Cargo.lock 的项目版本统一为 0.2.1。
- 新增简体中文发布说明 docs/releases/v0.2.1.md，概述 v0.2.0 以来的已合入变化与升级方式；更新架构文档的安装包示例。
- 沿用现有数据路径和发布工作流，通过功能分支 PR、最新提交必需检查、合并 main、v0.2.1 标签及 Windows Release 交付。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

无。本次仅更新发布元数据与文档，使用 `skip_specs: true`。既有行为由主规格定义，没有新增设计决策，不创建 design.md。

## Impact

涉及项目版本声明、两个 Cargo 锁文件、docs/architecture.md 和新增发布说明。不修改依赖版本或业务实现。安装版继续使用用户应用数据目录，便携版和直接运行 JAR 继续使用工作目录下的 data。无主规格同步；现有用户指南、历史文档和 v0.2.0 发布说明保留。
