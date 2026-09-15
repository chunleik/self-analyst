## Why

设置页点击“打开数据目录”后显示失败。原生命令已注册，但应用命令权限清单只声明 `save_document`，受管远程页面也没有打开数据目录的专用授权，需要修复权限链路并验证实际桌面调用。

## What Changes

- 补齐打开数据目录命令的权限声明与受管主窗口授权。
- 保留当前实例 origin、窗口与目录检查，不开放前端任意路径访问。
- 补充权限链路回归测试和桌面验收。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

无。本次恢复 `desktop-shell` 中 SPEC-DSK-DATA-001 已有行为，不改变规格契约，使用 `skip_specs: true`。

## Impact

影响 `self-analyst-desktop/src-tauri/build.rs`、capabilities、自动生成命令权限及相关测试。无需后端 API、数据迁移或新依赖；不修改 README 和主规格。
