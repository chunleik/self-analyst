## Why

用户要求实施、归档并发布 MCP 管理功能。将 Maven、Tauri 和 Windows 边车的版本统一为 beta.20，使用远端 Windows 工作流构建而不是本地打包。

## What Changes

- 版本统一为 `0.6.1-beta.20`，发布标签校验纳入新增的邮箱模块。
- 更新发行说明，明确 MCP/邮件功能、旧搜索退役及合成验证范围。
- GitHub PR 最新提交上的所有必需检查通过后合并；只在精确合并提交创建预发布标签。

## Capabilities

无新能力；本 change 仅处理发布元数据和既有发布校验。功能契约由 `add-mcp-tool-management` 维护。

## Impact

Maven、桌面应用、边车版本，发布说明与标签校验脚本；无本地打包。
