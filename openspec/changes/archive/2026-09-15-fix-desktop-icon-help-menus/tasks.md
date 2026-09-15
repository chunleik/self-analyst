## 1. 修复标题栏图片响应

- [x] 1.1 在 `self-analyst-app/src/test/java/com/selfanalyst/desktop/DesktopServerIntegrationTest.java` 补充真实 HTTP 图片回归用例：验证 PNG 内容类型、响应字节与资源一致、可成功解码，并覆盖 HTML、CSS、JS、SVG；先记录原实现的 PNG 失败证据。
- [x] 1.2 修改 `self-analyst-app/src/main/java/com/selfanalyst/desktop/DesktopServer.java`，直接返回静态资源原始字节并补齐 PNG 类型；运行 `mvn -pl self-analyst-app -am '-Dtest=DesktopServerIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test` 验证回归修复。

## 2. 实现手动检查更新

- [x] 2.1 在 `self-analyst-desktop/src-tauri/src/` 增加版本检测模块及可注入 HTTP 响应的测试，复用现有客户端并设置请求超时和响应上限；验证新版本、相同/较旧版本、0.2.10 与 0.2.9、预发布、构建元数据、非法标签、草稿、限流、404、超时和无效响应，测试不依赖公网。
- [x] 2.2 在 `titlebar.rs` 及必要命令注册/权限文件接入受管窗口的检查更新和固定项目发布页打开操作，后台执行阻塞请求；用 Rust 测试验证受管 origin、窗口和目标校验以及非受管页面拒绝；若直接声明 SemVer 依赖，同步 Cargo 清单和锁文件。
- [x] 2.3 修改 `index.html`、`titlebar-help.js`、必要样式和 `locales/zh.json`、`locales/en.json`，增加检查中、新版本、无需更新、失败重试、下载入口和 Web 说明；扩展 `titlebar-help.test.mjs` 覆盖四项键盘导航、并发去重、关闭后迟到响应、点击才打开下载页面及中英文文案，通过 Node 针对性测试。

## 3. 调整托盘关于入口

- [x] 3.1 修改 `self-analyst-desktop/src-tauri/src/lib.rs`，移除托盘关于项和事件分支，保留帮助关于；核对相关原生菜单测试或审阅入口，执行 `cargo test --manifest-path self-analyst-desktop/src-tauri/Cargo.toml`，并确认托盘菜单无关于、帮助关于仍可打开。

## 4. 综合验证与文档

- [x] 4.1 运行 `node --test self-analyst-app/src/test/js/titlebar-help.test.mjs self-analyst-app/src/test/js/favicon.test.mjs`、桌面 Rust 测试和 `mvn test`，记录结果；失败时定位并修复，不以静态源码断言替代图片 HTTP 验证。
- [x] 4.2 使用真实 Java 后端及桌面窗口进行视觉验收，保存图标正常、中文/英文帮助四项菜单、检查更新结果及托盘无关于的截图；验证 800×600 和高 DPI 下菜单可见且窗口/会话操作不被检查请求阻塞。
- [x] 4.3 同步 `README.md` 与 `README.zh-CN.md` 的帮助、更新检查和托盘关于说明；检查完整差异，确认英文版无新增中文段落且两版信息一致。
- [x] 4.4 使用 `openspec-sync-specs` 将两个增量规格同步到主规格，运行 `openspec validate fix-desktop-icon-help-menus --strict` 及相关主规格校验；完成任务后使用 `openspec-archive-change` 归档并记录验证结果。


## 验证记录

- PNG 回归由失败转为通过：旧响应 3536 字节，原资源及修复后响应 2007 字节。
- Java HTTP 集成测试 7 项、桌面 Rust 测试 35 项、最终 Node 针对性测试 17 项通过；全量 Maven 构建成功，当次 Node 套件 147 项通过。
- 真实 Java 后端与 Tauri 窗口已验收：中文、英文 800×600、150% WebView 缩放、真实更新检查、帮助关于、正式托盘菜单无关于。
- 原 GUI 测试载入器的入口点错误通过可运行的标题栏验收壳完成替代验证，详情见 docs/screenshots/desktop-help-updates/README.md。
- desktop-help、desktop-shell 主规格已同步并逐条核对；两份 README 内容与语言一致。
- docs/screenshots/titlebar-help/ 作为历史截图保留；本次截图及验证说明保存在 docs/screenshots/desktop-help-updates/。