## REMOVED Requirements

### Requirement: SPEC-ARCH-001、SPEC-ARCH-004 依赖与 ActivityWatch 查询边界
**Reason**: requirement 名与场景名含 ActivityWatch 品牌名，指代的却是本产品自研事件投影；OpenSpec 无法
单独重命名场景，故整体替换为同 ID 的新条款。行为契约不变。
**Migration**: 见本 capability 中「SPEC-ARCH-001、SPEC-ARCH-004 依赖与事件查询边界」，SPEC ID 与约束原样承接。

## ADDED Requirements

### Requirement: SPEC-ARCH-001、SPEC-ARCH-004 依赖与事件查询边界
应用服务依赖 SHOULD 通过构造器注入；进程级原生资源 MAY 使用具有关闭与失败降级路径的受控共享实例。
Agent 的事件投影查询 SHALL 通过注册的 EventQueryTools；窗口、AFK、标题与文件 heartbeat 等
采集链 MAY 直接调用本地兼容 HTTP API。

#### Scenario: Agent 查询事件投影
- **WHEN** Agent 需要列出 bucket、查询事件或执行 AQL
- **THEN** 调用通过 EventQueryTools 发往配置的兼容 API base URL，不直接访问数据库
