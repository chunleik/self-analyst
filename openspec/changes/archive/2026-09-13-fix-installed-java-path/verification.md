# 验证记录

- 路径回归测试：旧实现 2 项失败、1 项通过；修复后 3 项通过。
- `cargo test --manifest-path self-analyst-desktop/src-tauri/Cargo.toml --lib --offline`：25 项通过。
- `cargo clippy --manifest-path self-analyst-desktop/src-tauri/Cargo.toml --all-targets --offline -- -D warnings`：通过。
- 新增 `scripts/check-installed-startup.ps1`：真实旧 v0.2.3 安装壳退出码 1，后端日志为 `ClassNotFoundException`；修复版在中文空格路径完成安装资源选择、认证健康握手和隐藏窗口创建。
- NSIS 最终安装包通过 7-Zip 解包后，再次运行上述真实启动测试通过。后端 JAR 与已安装 v0.2.3 的 SHA-256 一致，复用已通过独立 JAR 冒烟的后端与 runtime。
- 安装包：`artifacts/SelfAnalyst_0.2.3_x64-setup.exe`；SHA-256：`17d9c0301e5a200bc9b7cd9dfe31d185b0873c4737c92dec354b70c98239fe47`。
- 测试仅在用户数据目录不存在时创建自身测试数据，关闭采集和远程功能，结束后清理；未覆盖用户本机安装。未执行真实安装/卸载操作，避免改写已有安装登记。
- 主规格同步增加 Windows 安装资源路径兼容要求与场景。现有 `docs/` 用户文档及历史档案保留，无需迁移或修改；本修复没有新增用户配置或操作流程。
- 本地修复包沿用 v0.2.3，尚未作为新版本发布，未提交、推送或合并代码。
