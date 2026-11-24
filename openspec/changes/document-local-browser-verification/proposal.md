## Why

当前 `AGENTS.md` 中列出了开发构建与测试命令，但未明确约定本地验证边界与桌面端打包职责，容易导致本地验证阶段执行耗时且不必要的桌面程序打包。现在需要在仓库指南中明确：本地验证仅在浏览器端进行，不做本地桌面程序打包，实际的桌面程序打包由 GitHub 工作流负责。

## What Changes

- 仅文档变更（不涉及产品代码或行为契约变化）：更新 `AGENTS.md`，在构建、测试与开发命令或测试规范相关章节中明确记录“本地验证时仅在浏览器端验证，不做本地桌面程序打包；实际的桌面程序打包放到 GitHub 执行”。

## Capabilities

### New Capabilities

- 无（纯仓库指南文档变更，已在 `.openspec.yaml` 中设置 `skip_specs: true`）。

### Modified Capabilities

- 无。

## Impact

- 仅影响仓库协作文档 `AGENTS.md`。
- 不修改任何 Java、Rust、JavaScript 源码、CI 工作流或系统运行时行为。
