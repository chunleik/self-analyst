## Context

参见 proposal.md。PR head 为 911f4c7，开始同步时 main 为 bf0188e。13 个冲突文件中，12 个为版本配置，DesktopServer 的 shutdown 同时增加了本体关闭与摘要增强停止。

## Goals / Non-Goals

目标：保持版本 0.6.0-beta.1、main 已有修复和本体能力，形成可正常更新原 PR 的合并提交。

非目标：重新实现本体、修改主规格、发布版本或合并 PR。

## Decisions

- 使用 merge 同步 main，而非 rebase/强推，保留 PR 历史及现有提交。
- 版本冲突选择 PR 的预发布版本，仅解决冲突块；双方其余配置保留。
- shutdown 同时执行本体 close 与 stopSummaryEnhancement，保留双方资源生命周期处理。
- 在仓库内隔离 worktree 中处理，不修改原本体 worktree 的未提交用户手册。

## Risks / Trade-offs

- 自动合并可能有语义冲突 → 复核 Agent、AppSession、桌面 UI、双语资源和 README，并运行本体及仪表盘回归和全量测试。
- 版本配置可能不一致 → 运行仓库现有版本检查及预发布渠道测试。
- main 在验证期间继续推进 → 推送前重新获取 main，必要时再次同步和验证。

## Migration Plan

完成本地验证并归档 change 后推送到 codex/ontology-v06；远端检查以更新后的 PR head 为准。失败时追加修复提交，不绕过必需检查。
