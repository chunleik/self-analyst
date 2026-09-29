## Why

main 仍用于 0.5.0 发布，本体功能需要从独立 0.6 分支提供可安装的 beta 包。发布 0.6.0-beta.1 供试用，并保持稳定发布通道不变。

## What Changes

- 将最新 main 同步到 codex/ontology-v06，保持 PR #76 未合并。
- 统一 Maven、Tauri、Node 和 Rust 项目版本为 0.6.0-beta.1。
- 允许本体 0.6 发行使用与标签一致的预发布版本标识，同步双语首页和 beta 发布说明。
- 使用现有 Windows Release 工作流从功能分支标签构建、校验并发布 Pre-release，包含便携包、安装包与校验和；不替代 Latest。

## Capabilities

### New Capabilities
- 无。

### Modified Capabilities
- `personal-ontology`: SPEC-ONTO-012 支持与发布标签一致的 beta/rc 版本标识。

## Impact

项目版本清单、两个 Cargo.lock、README、发行说明和本体规格；不改变功能实现、存储格式或发布工作流。main 保留 0.5 发布线，0.6 标签指向功能分支提交。
