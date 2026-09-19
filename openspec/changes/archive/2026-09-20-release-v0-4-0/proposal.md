# 提案

## Why

将 v0.3.0 之后已合并的配置体验变更发布给用户：配置窗口三页签重排（模型设置 / 高级配置 / 运行数据）、打开数据目录权限二轮修复，以及停用单次输出上限。其中模型设置 API 不再返回或接受 `maxTokens`、`llm.max-tokens` 与 `LLM_MAX_TOKENS` 退役属于不兼容变更，参照 v0.2.0/v0.3.0 处理不兼容变更的先例升级次版本号，计划发布 v0.4.0。

## What Changes

- 将 Maven、桌面 Node/Tauri 项目、两个 Rust 工程及锁文件中的项目版本统一为 0.4.0。
- 新增 docs/releases/v0.4.0.md，说明配置三页签重排、打开数据目录修复、单次输出上限停用（含 BREAKING 行为与升级指引）及从 v0.3.0 升级的步骤。
- 更新 docs/architecture.md 的当前安装包示例为 0.4.0。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

无。本 change 仅更新发布元数据和文档，业务行为由已合并功能变更（`2026-09-19-reorganize-config-editor-layout`、`2026-09-20-fix-open-data-directory-permission`、`2026-09-20-omit-llm-output-token-limits`）及主规格维护，设置 skip_specs: true。

## Impact

涉及项目版本配置、项目锁文件、发布说明和架构文档。沿用现有 Windows 便携 ZIP、NSIS 安装包和 SHA-256 文件发布流程，不升级第三方依赖。
