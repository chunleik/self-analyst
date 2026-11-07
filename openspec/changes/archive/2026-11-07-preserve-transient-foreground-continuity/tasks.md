## 1. 回归与实现

- [x] 1.1 在 `ContentWatcherTest` 补充空窗口及零句柄回归，覆盖捕获、UIA 完成确认、心跳、发送前复查、会话连续性、原请求重试、排除应用及在途查询失效；先在旧实现运行定向测试确认失败。
- [x] 1.2 在 `ContentWatcher` 分离快照失效与隐私丢弃，保留允许应用 pending；运行 `mvn -pl self-analyst-content -am '-Dtest=ContentWatcherTest,ContentHeartbeatDeliveryTest' '-Dsurefire.failIfNoSpecifiedTests=false' test` 验证新旧测试全部通过。

## 2. 集成验证与规格

- [x] 2.1 运行 `mvn test` 验证 Java 与桌面 Node 测试，记录成功、跳过或环境限制；检查 `git diff --check`。
- [x] 2.2 同步 `openspec/specs/title-capture/spec.md`，运行本 change 与全部主规格的严格校验，核对归档条件并归档；在归档验证记录中说明无需修改其他用户文档及历史文档。
