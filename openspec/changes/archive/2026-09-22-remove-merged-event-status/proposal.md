## Why

顶部“合并事件”是偏底层的存储状态，用户要求移除该显示，简化全局状态栏。

## What Changes

- 移除顶部合并事件文字、状态灯、提示及关联 DOM 更新。
- 保留事件存储、后端状态接口和设置中的运行数据页。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

- `merged-event-storage`: 明确顶部不再显示合并事件状态项，运行数据页继续提供存储信息。

## Impact

影响桌面 HTML、初始化绑定、状态刷新、中英文资源与已有 Node 状态测试。无后端或依赖变更。
