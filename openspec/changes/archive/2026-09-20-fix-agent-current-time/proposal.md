## Why

Agent 初始化时将当时的本地时间写入系统提示词，长时间运行后回答“现在几点”会把启动时间误认为当前时间，并据此错误判断事件的新旧。

## What Changes

- 修改既有行为：每轮 Agent 调用时向模型提供本轮的本地时间，沿用有效语言与时区格式。
- 增加回归测试，覆盖同一 Agent 连续调用时时间前进的情况。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

- `internationalization`：当前时间提示除语言格式外，还必须按每轮调用刷新。

## Impact

影响 Agent 系统提示词装配及相关测试；不改变桌面 API、事件存储或采集范围。
