## 1. 修复与验证

- [x] 1.1 新增真实 PowerShell 调用的 `resolve-app-jar.test.mjs`，先复现正式 JAR 与 shaded 共存时的失败，覆盖 SNAPSHOT、缺失、歧义及只读行为。
- [x] 1.2 修改 `scripts/resolve-app-jar.ps1`，在候选计数前排除 shaded 中间产物，确认针对性测试通过。
- [x] 1.3 运行完整 Node 测试与 OpenSpec 严格校验，记录结果后按流程归档；此修复不改变主规格。


