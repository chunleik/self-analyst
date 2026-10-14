## 1. 版本发布时间和弹窗

- [x] 1.1 修改受保护的 Tauri 帮助操作，返回本地关于信息并读取精确版本 published_at；补充 Rust 的 beta、错配、草稿、缺失、网络错误、超时及请求隐私测试。
- [x] 1.2 复用 titlebar-help.js 的应用内弹窗，添加中英文时间、本地时区、开发版和不可用状态；Node 测试验证缓存、时间校验、重复打开、关闭及迟到响应。
- [x] 1.3 更新关于的旧原生验收分支以及双语 README，验证现有内容未丢失、Web 行为保持和翻译一致。

## 2. 验证和规格

- [x] 2.1 运行相关 Node/Rust 测试和 Rust 格式检查，再运行桌面 Node 全量回归；记录通过和环境限制。
- [ ] 2.2 在真实 Chromium 中检查中英文、窄窗口、加载、离线、开发版、重开和关闭流程，保存截图并记录 Windows 原生集成验证边界。
- [ ] 2.3 同步 desktop-help 主规格并运行 openspec validate --all --strict；核对任务后归档。

## 当前验证状态

- `mvn --batch-mode test`：BUILD SUCCESS（Java 1109 项：1105 通过、4 个既有条件跳过；Maven 集成 Node 也通过）。
- `node --test self-analyst-app/src/test/js/*.test.mjs scripts/*.test.mjs`：273 通过、32 跳过、0 失败。
- 32 个跳过为现有 Windows 条件：8 个 JAR 路径、23 个发布版本脚本、1 个 Windows GitHub Actions/Edge UI 冒烟；不计为已通过。
- `openspec validate --all --strict`：36 项通过；desktop-help 主规格已同步。
- 官方 Rust stable 1.99.0 已安装。隔离 crate 直接引用生产 `about.rs`、`updates.rs` 并逐字提取 `HelpResponse`：13 项测试通过（新增发布时间 7 项、既有更新 4 项、序列化兼容 2 项），`cargo clippy --all-targets -- -D warnings` 通过。
- 桌面壳和 sidecar 的 `cargo fmt -- --check` 均通过。
- 已尝试完整桌面 `cargo check --locked --target x86_64-pc-windows-msvc`，在 `ring` 原生依赖构建因 Linux 环境缺 Microsoft `lib.exe` 中止。上述独立模块验证不能代替完整 Windows/Tauri 编译和集成验收。
- 用户授权后清理本任务 3 个专属 debug 输出目录，删除前确认无源码、用户数据、符号链接或其他活动构建；删除后确认全部不存在。清除占用 1,500,426,240 字节（约 1.40 GiB），保留源码、验证 crate、工具链和测试/清理报告。
- Chromium 使用真实资源及合成 Tauri 数据的视觉检查已尝试，浏览器在创建本地 IPC socket 时被环境拒绝，未产生页面或截图；Windows/Tauri 原生集成也未运行。
- 因上述验证未完成，保持 change 未归档，不声称全部验收完成。
