# 提案

## Why

v0.4.0 于 2026-09-19 发布，tag 打在配置体验变更合并之后，未包含其后合并的 Agent 当前时间修复（`2026-09-20-fix-agent-current-time`）：长时间运行后 Agent 会把启动时间当作当前时间回答并误判事件新旧。该缺陷影响已发布 0.4.0 用户的日常问答体验，且发布仅一天，重新以 0.4.0 版本号切出包含修复的发布产物，避免为单日缺陷额外引入 0.4.1 版本号。

## What Changes

- 在 `docs/releases/v0.4.0.md` 的更新内容中补充 Agent 当前时间每轮刷新的修复条目，使发布说明与重发产物内容一致。
- 合并 Agent 当前时间修复后，删除既有 `v0.4.0` tag 与 GitHub Release，在新 main HEAD 重打 `v0.4.0` tag 触发 Windows 发布工作流，重建便携 ZIP、NSIS 安装包及 sha256 校验和。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

无。本 change 仅更新发布元数据和文档并重发既有版本，业务行为由已合并变更（`2026-09-20-fix-agent-current-time`）及主规格维护，设置 skip_specs: true。

## Impact

涉及 `docs/releases/v0.4.0.md` 发布说明与 Git tag / GitHub Release 元数据；不改版本号、不改代码、不改主规格。已下载旧 0.4.0 产物的用户需重新下载，旧产物 sha256 失效。
