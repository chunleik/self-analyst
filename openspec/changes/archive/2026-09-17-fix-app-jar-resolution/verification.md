# 验证记录

- 修复前：8 项真实 PowerShell 回归中 4 项失败，分别覆盖 shaded 共存、不同中间内容、SNAPSHOT 和仅有 shaded 的情况。
- 修复后：8/8 针对性测试通过；在 self-analyst-app 下执行 node --test，159/159 测试通过。
- PR #54 首次 CI 发现：Windows runner 的 TEMP 为 8.3 短名（`RUNNER~1`），Node 侧期望路径为短名而 PowerShell 输出长名，4 项路径断言失败。改为两侧经 `realpathSync.native` 取最终长路径后比较；本机以短名 TEMP 模拟 CI 复跑 8/8 通过，完整 JS 套件 158/158 通过。
- 实际 target 解析返回 self-analyst-app-0.2.8.jar。
- OpenSpec 严格校验和 git diff --check 通过。
- 改动只涉及构建脚本及测试，不改变产品行为或主规格。无需更新 README；解析规则说明保留在脚本注释中。
