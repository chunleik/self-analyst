# 验证记录

- 修复前：8 项真实 PowerShell 回归中 4 项失败，分别覆盖 shaded 共存、不同中间内容、SNAPSHOT 和仅有 shaded 的情况。
- 修复后：8/8 针对性测试通过；在 self-analyst-app 下执行 node --test，159/159 测试通过。
- 实际 target 解析返回 self-analyst-app-0.2.8.jar。
- OpenSpec 严格校验和 git diff --check 通过。
- 改动只涉及构建脚本及测试，不改变产品行为或主规格。无需更新 README；解析规则说明保留在脚本注释中。
