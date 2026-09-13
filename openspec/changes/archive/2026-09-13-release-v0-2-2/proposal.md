## Why

将最新 main 中已合入的独立模型设置和配置文档更新交付为 v0.2.2，确保所有分发入口使用一致版本。

## What Changes

- 将六个 Maven POM、桌面 package.json 和 tauri.conf.json、桌面及边车 Cargo.toml/Cargo.lock 的项目版本统一为 0.2.2。
- 新增 docs/releases/v0.2.2.md，说明 v0.2.1 以来的变化、升级与下载方式，并更新架构文档中的安装包示例。
- 沿用功能分支 PR、最新提交必需检查、合并 main、版本标签及 Windows Release 工作流完成交付。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

无。本次只更新版本元数据和发布文档，设置 skip_specs: true；既有能力已由主规格覆盖。

## Impact

涉及项目版本声明、两个 Cargo 锁文件、架构文档和发布说明；不改变依赖和运行行为，无需同步主规格。沿用现有安装版及便携版数据目录。保留用户指南、历史文档和以前版本发布说明。
