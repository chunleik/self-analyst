## Why

将已合并 PR #114 的时间轴样式与展开状态修复发布为下一版 beta。现有最高预发布为 v0.6.1-beta.4，最新稳定版 v0.6.0 不变，用户已明确授权合并并发布新 beta。

## What Changes

- 基于 main 841e480f16a9650cfe8afa5e3bf335aa364cafc6，同步 13 处项目自身版本为 0.6.1-beta.5，不修改依赖。
- 新增中文发行说明，说明统一主摘要样式、刷新保留展开状态、日期/时区隔离与升级注意事项，明确视觉验收尚未完成。
- 经发布准备 PR 最新提交必需检查通过并合入 main 后，用全新 v0.6.1-beta.5 标签触发现有 Windows Release；核验预发布属性、分发文件及校验和。
- 不修改发布工作流、采集范围、数据格式或摘要生成，不覆盖旧标签/Release，不进入稳定发布通道。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

无。沿用 windows-release SPEC-REL-002、SPEC-REL-005 和已同步的 desktop-summary；纯版本与发行说明变更设置 skip_specs: true。

## Impact

13 处版本字段、中文发行说明与发布准备记录。现有工作流构建 Windows 便携 ZIP、NSIS 安装包及对应 SHA-256。保留全部历史发布。
