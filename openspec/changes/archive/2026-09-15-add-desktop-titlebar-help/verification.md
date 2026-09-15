# 验证记录

## 当前结论

2026-09-15：帮助菜单、桌面/Web 布局、原生桥接和回归测试已实现；自动化测试及构建通过。用户已确认其余操作均完成验收，原生验收以用户反馈作为最终证据；多显示器按用户决定本次豁免。实施与验收已具备归档条件，归档不等同于完成 Git 提交或发布。

用户随后在独立预览窗口中确认「拖动和贴靠都没问题」，这两项已取得用户实机反馈。用户要求移除帮助文字右侧的下拉三角，已更新共享入口及设计。该反馈不扩大为全部窗口操作、托盘和多显示器验收通过；此前带三角的截图属于调整前版本。

用户进一步确认「英文原生界面已经验收」，结合中文原生截图及既有 8 组 DOM 布局检查，界面验收任务 4.2 已完成。用户明确表示目前没有多显示器，本次不做该项验收；多显示器实机验收列为本次豁免，不再作为归档阻塞项，也不记为通过。多显示器适配行为契约仍保留。

## 已完成验证

| 验证 | 结果 |
| --- | --- |
| `node --test self-analyst-app/src/test/js/titlebar-help.test.mjs` | 10 项通过，覆盖环境分支、开关、焦点、键盘、固定链接、失败、关于及窗口状态同步 |
| self-analyst-app 目录 `node --test` | 144 项通过 |
| `cargo test --manifest-path self-analyst-desktop/src-tauri/Cargo.toml` | 31 项 Rust 测试通过；包含 4 项标题栏/帮助专项测试，验证动态端口来源与生成 ACL |
| `cargo build --manifest-path self-analyst-desktop/src-tauri/Cargo.toml` | 通过 |
| `cargo build --manifest-path self-analyst-desktop/src-tauri/Cargo.toml --features titlebar-review --bin titlebar-review` | 隔离验收程序构建通过 |
| JDK 21，`mvn -s C:/Users/10478/.m2/settings-aliyun.xml test` | 全部模块 BUILD SUCCESS；应用模块 460 项，0 失败、0 错误、1 项跳过 |
| 三个帮助公开链接 HEAD 检查 | 中文 README、英文 README、Issues 均 HTTP 200 |
| Edge 无头布局和实际 DOM 键盘检查 | 桌面布局模拟/Web × 中英文 × 800×600/1200×800，共 8 组通过；文件入口开启，菜单无越界或导航重叠 |
| 双语 README 差异检查 | 帮助说明一致，英文新增内容无中文说明 |
| 用户实机反馈 | 拖动与贴靠正常；英文原生界面验收完成，中文界面已有用户提供截图 |
| 用户最终验收确认 | 用户回复「其他操作均完成验收」，本次窗口操作、显示缩放及托盘/单实例验收据此关闭；不虚构工具测量数据或未提供的系统版本 |

布局截图位于 `docs/screenshots/titlebar-help/`。`desktop-layout-*` 使用真实页面和模拟原生桥接；它们只验证桌面布局，不证明原生鼠标命中、贴靠或窗口控制。Web 关于的截图不包含生产用户信息。

构建日志位于忽略目录 `self-analyst-desktop/src-tauri/target/`，包括 node-tests-titlebar.log、rust-tests-titlebar.log、rust-build-titlebar.log、maven-tests-titlebar.log、titlebar-review-build.log、titlebar-visual-results.json。

## 发现与修复

- 隔离 lib test 缺少 Common Controls 激活清单，原生入口加载失败；直接给全部链接目标添加清单又导致正式 EXE 清单重复。已改为带 feature 的独立验收二进制，复用正常程序资源清单，移除全局链接参数。默认构建仍以 SelfAnalyst 为入口。
- 语言恢复测试的沙箱没有 window，新加的标题栏调用导致初始化被中断；已加入环境检查，原有恢复测试通过。
- Node 自动发现测试目录中的示例 HTTP 服务并一直等待；已用 NODE_TEST_CONTEXT 防止测试进程启动该服务，全量测试正常结束。
- 标题栏导致正文和会话抽屉高度偏移；高度计算已纳入标题栏变量。

## 原生验收与本次豁免

此前隔离窗口已创建，并可通过可访问树读取帮助、最小化、最大化等控件。Computer Use 的点击连续返回 `coordinate input geometry is unavailable`；重新选择与激活后仍失败。截图也没有可靠捕获目标窗口，不能作为原生验收证据。此前用户按 Esc 中断的操作没有计作成功；本轮重试失败后停止原生输入操作。

以下此前待验收内容现已由用户最终确认完成；结果来源为用户手动验收，不是 Computer Use 自动验证：

- 双击最大化、最大化后拖动恢复、边缘缩放、Alt+Space 系统菜单、最小化与恢复；一般拖动和贴靠此前已单独确认正常。
- 正式主窗口关闭到托盘、托盘恢复、退出、自动隐藏启动和第二实例唤起。
- 本次清单中的显示缩放验收；用户未提供各档位的独立截图和系统版本，本记录不补造细节。

混合 DPI 多显示器验收因无设备由用户明确免除本次执行，不属于上面的待完成项；仍没有该场景的实测证据。

## 隔离预览复现

先从仓库根运行 `node self-analyst-app/src/test/js/titlebar-review-server.mjs`，读取输出端口；需要英文时先设置 `SELF_ANALYST_TITLEBAR_REVIEW_LANGUAGE=en`。在另一终端设置 `SELF_ANALYST_TITLEBAR_REVIEW_PORT` 为该端口，再运行 `cargo run --manifest-path self-analyst-desktop/src-tauri/Cargo.toml --features titlebar-review --bin titlebar-review`。验收程序不启动 Java、不访问生产数据、不注册启动项，最多运行 15 分钟；它不模拟生产托盘生命周期，因此相关验收须另外在隔离的正式分发环境执行。

## 规格与交付

desktop-help 与 desktop-shell 增量已同步到主规格，并在归档前逐项核对一致。严格校验结果由本轮 CLI 确认。`docs/screenshots/titlebar-help/` 保留布局验收示例与说明，双语 README 保留用户帮助说明；旧版带三角截图为历史布局证据，不视为最终像素效果。未提交、推送或创建 PR；后续交付仍须等待最新提交的必需远端检查（含 Windows 全量验证）。
