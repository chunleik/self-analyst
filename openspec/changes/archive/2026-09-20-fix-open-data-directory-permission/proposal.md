## Why

设置页点击“打开数据目录”后显示失败。原生命令已注册，但应用命令权限清单只声明 `save_document`，受管远程页面也没有打开数据目录的专用授权，需要修复权限链路并验证实际桌面调用。

## What Changes

- 补齐打开数据目录命令的权限声明与受管主窗口授权。
- 保留当前实例 origin、窗口与目录检查，不开放前端任意路径访问。
- 补充权限链路回归测试和桌面验收。
- 移除开发构建中固定的 `devUrl`，避免默认端口的后端页面被框架归类为 Local 后无法匹配 remote-only 的受管命令权限；页面继续由后端握手地址打开。
- 运行数据没有旧库备份时隐藏底部清理说明块，保留容量卡片中的真实零值，避免将正常状态突出为黄色提示。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

无。本次恢复 `desktop-shell` 中 SPEC-DSK-DATA-001 已有行为，不改变规格契约，使用 `skip_specs: true`。

## Impact

影响 `self-analyst-desktop/src-tauri/build.rs`、capabilities、`tauri.conf.json`、自动生成命令权限、运行数据渲染及相关测试。无需后端 API、数据迁移或新依赖；既有布局规格未要求无备份时显示独立提示，保留零值统计已准确表达状态，不修改主规格。
