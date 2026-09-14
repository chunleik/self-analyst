## Context

发布动机见 proposal.md。Maven、Tauri 和两个 Rust 工程分别保存版本号，安装包名称读取桌面版本。

## Goals / Non-Goals

统一跨模块发布标识；不升级第三方依赖、不改变数据格式或行为契约。

## Decisions

沿用现有版本发布方式，集中更新项目版本及本项目的 Cargo.lock 条目，避免全局替换第三方依赖中相同的版本字符串。发布说明从 v0.2.4 到 main 的合并记录生成。

## Risks / Trade-offs

版本遗漏或构建失败 → 本地核对差异、Maven validate 和 OpenSpec 严格校验；PR 最新提交的 Windows 全量验证成功后合并，标签工作流再次运行发布门禁。

## Migration Plan

归档准备变更并提交 PR，检查成功后合并，从合并提交推送 v0.2.5 标签。核对正式发布的 ZIP、安装包和两份 SHA-256 文件，再更新 Release 中文说明。失败时修复原因，不覆盖既有版本标签。
