## Why

直接推送 main 可能在必需的 Windows 检查完成前进入主分支。需要在仓库指南中明确通过功能分支和 PR 交付，先通过检查再合并。

## What Changes

- 在 AGENTS.md 的提交与拉取请求规范中记录“功能分支 → 推送分支 → 创建 PR → 必需检查通过 → 合并 main”。
- 明确检查必须对应 PR 最新提交；分支落后时先同步 main 并等待重新验证。
- 明确本地测试不替代远端必需检查，不使用管理员绕过或强制推送规避保护。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

无。本次只修改开发指南，不改变产品行为，使用 skip_specs；无架构或技术设计变化，不创建设计文档。

## Impact

仅修改 AGENTS.md 并保留本次 OpenSpec 记录；不修改 CI 工作流或 GitHub 分支保护设置。
