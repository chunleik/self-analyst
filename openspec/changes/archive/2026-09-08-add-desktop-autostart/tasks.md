## 1. 自启动注册与托盘入口

- [x] 1.1 在 `self-analyst-desktop/src-tauri/src/` 封装自启动入口读写及归属判断，按需更新 `Cargo.toml`；以隔离存储适配器测试默认关闭、开启/关闭、其他分发路径、中文空格路径、命令长度超限及读写失败，确认不写入真实登录入口。
- [x] 1.2 在 `self-analyst-desktop/src-tauri/src/lib.rs` 增加托盘勾选项及刷新、错误展示；验证勾选以回读结果为准、不可用状态可见、其他路径替换有说明，关闭开关不结束本次运行。

## 2. 启动来源与窗口唤起

- [x] 2.1 在桌面壳入口解析 `--autostart` 并把可见性传到窗口创建；添加启动模式及窗口策略测试，验证手动可见、自动隐藏、后端失败沿用清理流程，执行 `cargo test --manifest-path self-analyst-desktop/src-tauri/Cargo.toml`。
- [x] 2.2 扩展 `lib.rs` 单实例机制及必要辅助模块，实现受当前用户和会话约束的固定唤起信号、待显示状态和有界等待；以隔离命名对象测试初始化前后请求、自动重复启动、通信失败及退出句柄回收，确认第二实例从不启动后端。
- [x] 2.3 统一托盘和跨进程恢复窗口的主线程处理；在 Windows 桌面验证隐藏与最小化窗口均可恢复、自动重复启动不改变焦点，记录截图或操作证据。

## 3. 安装与便携集成

- [x] 3.1 在 `self-analyst-desktop/src-tauri/tauri.conf.json` 和新增 NSIS 钩子中接入启动项归属清理；扩展 `scripts/check-installer.ps1`，验证首次安装不注册、原位置升级保留启用/关闭状态、卸载清理自身入口但保留其他分发入口和用户数据。
- [x] 3.2 为便携路径和系统工作目录增加启动冒烟验证脚本；验证空格/中文路径、从不同工作目录启动、目录移动后显式重新开启均解析正确的后端及数据位置，注册操作仅在隔离测试账户或可恢复测试环境执行。

## 4. 验收与文档

- [x] 4.1 在用户已明确批准的当前 Windows 账户进行真实注销/登录验收，使用可恢复的临时测试目录和关闭采集的配置：启用时只出现托盘且后端就绪、关闭后不启动、再次手动打开唤起已有窗口；确认只有一个受管后端，并验证后端故障时无主窗口且有诊断日志，保存验收记录与托盘截图。（行为验收、系统证据及原生菜单截图均已完成；见 verification.md。）
- [x] 4.2 运行桌面壳完整 `cargo test --manifest-path self-analyst-desktop/src-tauri/Cargo.toml` 和安装/便携冒烟；若实施触及 Java 或跨模块行为，追加 `mvn test`，记录结果及环境限制。
- [x] 4.3 将 delta 同步到 `openspec/specs/desktop-shell/spec.md`，更新 `README.md` 的自启动操作、系统策略限制、便携移动及回退说明，按需补充 `docs/architecture.md` 生命周期描述；逐项核对新增及修改场景与验收证据，运行 `openspec validate add-desktop-autostart --strict`。
