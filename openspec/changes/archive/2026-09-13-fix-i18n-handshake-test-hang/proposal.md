## Why

Windows CI 的语言握手测试在客户端连接前超时时，服务端仍无限等待 accept，主线程 join 无法结束，最终耗尽 45 分钟作业时限。需要修复测试收尾，并区分连接失败与已连接后的响应超时。

## What Changes

- 测试服务端使用有界 accept 和读写等待，确保没有客户端连接也能退出。
- 拆分认证握手、响应超时和无连接收尾回归场景，响应超时必须确认收到请求并保持连接直到客户端返回。
- 仅修改测试，不改变生产语言请求、网络配置或 CI 时限。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

无。沿用 SPEC-I18N-NATIVE-001，设置 skip_specs: true，不改变主规格行为。

## Impact

仅影响 self-analyst-desktop/src-tauri/src/i18n.rs 的测试模块；不增加依赖，不涉及 API 或用户文档调整。
