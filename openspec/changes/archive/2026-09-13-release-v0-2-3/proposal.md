## Why

将 `v0.2.2` 之后已经合入 `main` 的官网、Agent 文档生成和用户运行数据存储改进统一交付为 `v0.2.3`，确保源码版本、Windows 分发包及发布说明保持一致。

## What Changes

- 将 Maven、桌面应用及两个 Rust 工程的项目版本统一更新为 `0.2.3`。
- 新增 `docs/releases/v0.2.3.md`，说明 `v0.2.2` 以来的变化、升级方式、数据目录边界和下载文件，并更新架构文档中的安装包示例。
- 运行发布前质量门禁，通过功能分支 PR 交付版本元数据；合并后在最新 `main` 创建 `v0.2.3` 标签，由 Windows Release 工作流构建、校验并发布分发包。
- 不包含保存在 `hold/refine-chat-memory-experience-2026-09-13` 标签中的长期记忆体验候选方案，也不将该方案合并到 `main`。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

无。本次只更新版本元数据和发布文档，不改变可观察行为；设置 `skip_specs: true`，无需增量规格。

## Impact

涉及项目版本声明、两个 Cargo 锁文件、架构文档和发布说明；不修改业务代码、API、依赖或用户数据。发布基于最新 `main`，沿用既有 Windows Release 工作流和 GitHub Release 资产结构。
