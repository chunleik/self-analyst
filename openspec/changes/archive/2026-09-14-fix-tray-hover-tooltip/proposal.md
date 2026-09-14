## Why

用户反馈 Windows 托盘图标悬停时出现空白提示。桌面壳创建托盘时设置了图标和点击事件，但没有设置 tooltip，缺少可供系统展示的产品名称。

## What Changes

- 修复既有托盘行为：鼠标悬停时显示固定产品名称 `SelfAnalyst`。
- 补充提示文字的回归验证与 Windows 悬停验收。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

- `desktop-shell`：系统托盘契约增加非空的产品名称悬停提示。

## Impact

涉及 `self-analyst-desktop/src-tauri/src/lib.rs` 的托盘初始化、相关回归测试和桌面壳规格。无需新增依赖或调整后端接口；当前运行程序需使用重新构建的桌面壳并重启后才能生效。
