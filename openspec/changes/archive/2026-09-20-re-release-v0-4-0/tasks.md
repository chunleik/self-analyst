# 任务

## 1. 发布说明

- [x] 1.1 在 `docs/releases/v0.4.0.md` 更新内容中补充 Agent 当前时间每轮刷新的修复条目。
- [x] 1.2 运行 OpenSpec 严格校验和 `git diff --check`，确认无行为契约变化、无空白错误。

## 2. 外部交付

- [ ] 2.1 确认 PR #58（agent 当前时间修复）必需检查全部通过并合并进 main。
- [ ] 2.2 提交并推送 re-release 分支，创建 PR，关联 re-release-v0-4-0 和验证结果，等待必需检查通过后合并 main。
- [ ] 2.3 删除远端既有 `v0.4.0` tag 与 GitHub Release，在新 main HEAD 重打 `v0.4.0` tag 并推送，触发 Windows 发布工作流。
- [ ] 2.4 等待发布工作流成功，核验 Release 产物（便携 ZIP、NSIS 安装包、sha256）已更新，并记录验证结果。
