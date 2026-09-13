## Why

Windows 安装版将 Tauri 返回的扩展长度路径直接交给 Java，导致完整的后端 JAR 报主类找不到，应用无法启动。相同 JAR 使用普通路径的冒烟测试通过，需要修复桌面壳与 Java 的路径边界。

## What Changes

- 修复后端 JAR 参数的 Windows 路径兼容性，保留安装资源选择和用户数据目录规则。
- 补充路径转换及真实 Java 启动回归验证，构建可供本地安装的新安装包。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

- `desktop-shell`：明确安装资源返回扩展长度路径时后端仍须正常加载，路径包含空格或中文时不得失真。

## Impact

影响桌面壳 Java 启动参数、Rust 回归测试、安装验证和桌面壳主规格；不修改后端业务逻辑、用户数据或公开 API。
