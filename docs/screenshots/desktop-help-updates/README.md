# 图标、更新检查与托盘菜单验收

对应 OpenSpec change：`fix-desktop-icon-help-menus`。验收日期：2026-09-15。

## 实际窗口证据

使用本次构建的 `titlebar-review` Tauri 窗口连接真实 `DesktopServer`，后端使用独立临时测试目录，未启用采集器。图片由正式 Java 静态资源路由返回。更新检查使用正式受管命令和公开版本服务。

- [中文帮助菜单与正常图标](help-zh.png)：使用指南、反馈问题、检查更新、关于。
- [英文 800×600 菜单](help-en-800.png)：四项均完整可见。
- [150% WebView 缩放](help-zh-150.png)：图标、帮助项目和窗口控件保持正常；这是应用缩放模拟，未修改操作系统 DPI 设置。
- [中文更新结果](update-zh.png)、[英文小窗口更新结果](update-en-800.png)：本次测试显示当前 0.2.6、最新稳定版 0.2.7；该版本结果仅描述截图时的查询。
- [帮助关于](about-zh.png)：显示桌面版本和临时后端地址。
- [正式托盘菜单](tray-menu-zh.png)：只包含显示窗口、Web版桌面、开机自启动和退出；验收壳调用正式菜单构造函数，同时断言四个菜单 ID，无注册表读写。

## 自动验证

- 原实现的 PNG HTTP 回归测试失败：源图片 2007 字节，旧接口响应 3536 字节。修复后图片字节完全一致、类型为 `image/png`，并可由 ImageIO 解码；HTML、CSS、JS、SVG 响应保持一致。
- `mvn -s C:/Users/10478/.m2/settings-aliyun.xml -pl self-analyst-app -am '-Dtest=DesktopServerIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`：7 项通过。
- `mvn -s C:/Users/10478/.m2/settings-aliyun.xml test`：全部模块成功；当次 Node 套件 147 项通过，Java 保留既有 1 项跳过。
- `cargo test --manifest-path self-analyst-desktop/src-tauri/Cargo.toml`：35 项通过，包含版本排序、发布类型、HTTP 状态、非法响应、响应上限、超时和并发限制。
- `node --test self-analyst-app/src/test/js/titlebar-help.test.mjs self-analyst-app/src/test/js/favicon.test.mjs`：17 项通过，包含中文/英文、重试、重复检查、关闭后的迟到结果及其他帮助消息不被覆盖。
- OpenSpec 变更及 `desktop-help`、`desktop-shell` 两份主规格的严格校验通过。

## 验收入口

`titlebar-review` 的 `SELF_ANALYST_TITLEBAR_REVIEW_PORT` 指向临时真实后端；`SELF_ANALYST_TITLEBAR_REVIEW_SMALL=1` 设置 800×600；`SELF_ANALYST_TITLEBAR_REVIEW_ZOOM=1.5` 设置放大显示；`SELF_ANALYST_TITLEBAR_REVIEW_TRAY=1` 在窗口聚焦时展示正式托盘菜单。这些选项仅在 `titlebar-review` 特性中编译，不改变正式启动行为。

旧 `native-menu-review` 的 GUI 测试可执行文件在本机出现 `STATUS_ENTRYPOINT_NOT_FOUND`，因此托盘视觉检查改由上述可正常运行的验收壳完成，保留正式菜单构造和菜单 ID 断言。普通 Rust 测试未受影响。

现有 `docs/screenshots/titlebar-help/` 保留为原始标题栏改造的历史截图；此次新行为以本目录和主规格为准。
