# 验收记录

日期：2026-09-29。变更：add-language-restart-button。

## 自动化验证

- `node --test self-analyst-app/src/test/js/*.test.mjs`：203 项通过。
- `cargo test --manifest-path self-analyst-desktop/src-tauri/Cargo.toml`：40 项库测试通过，二进制及文档测试通过。覆盖原生命令来源限制、生成 ACL、重复请求、先清理后启动、启动参数与工作目录、准备失败后重试及启动错误传播。
- Java 21 下 `mvn test`：所有模块 BUILD SUCCESS；既有依赖权限相关测试按原测试规则跳过。首次沙箱内因 Maven Central 网络权限失败，经授权在沙箱外执行成功。
- `mvn package -DskipTests`、`cargo build --release --manifest-path self-analyst-desktop/src-tauri/Cargo.toml`：构建通过。
- `openspec validate add-language-restart-button --strict`：通过；`openspec validate --specs --strict`：30 项主规格通过。
- `git diff --check`：通过；英文和中文 README 差异已核对。

## 真实桌面重启

使用 `.tmp/restart-qa/app/` 内的便携测试副本及独立数据根，配置端口 5819，关闭窗口/AFK/标题/文件采集、Wiki、Embedding 和搜索，不配置模型密钥。已有安装应用触发正常单实例限制后，改用项目既有 `document-review` 功能编译 release；该功能只切换单实例命名空间，重启及启动流程仍为正式代码。未操作安装版配置或数据。

通过 Computer Use 实际操作设置、语言选择、保存和立即重启，结果：

- 中文选择 English，保存后出现立即重启；重启后显示英文主窗口。
- 英文配置改回中文，保存后显示 Restart now；重启后显示中文主窗口。
- 用 `--autostart` 启动测试副本，确认窗口隐藏；手动唤起后将语言保存为 auto，点击立即重启，新实例以 `automatic=false` 启动并显示主窗口，系统解析结果为中文。
- 日志记录三次新实例取得主实例资格、后端健康检查通过，旧后端均先正常关闭。最后确认旧桌面 PID 40220、50160、26420、22896 均不存在，测试副本只剩一个桌面实例及其新后端。
- 配置始终保存在同一便携数据根，端口及关闭采集的配置不变。测试完成后只清理测试副本进程。

## 界面与键盘

真实桌面截图保存在 `docs/screenshots/language-restart-zh.jpg`。独立浏览器使用现有内存假数据预览，在中文和英文、800×600 和 390×700、普通浏览器及模拟原生桥接两种状态下验收：可见操作按钮均在视口内，无重叠或整体横向滚动；原生状态按钮可聚焦；普通浏览器隐藏重启按钮并显示手动说明。英文窄窗口截图保存在 `docs/screenshots/language-restart-en-390.png`。

验收发现原有窄窗口样式继承 column 布局与 wrap 冲突，已在配置工作区显式使用 row 并复验通过。

## 规格和文档

internationalization、desktop-shell 主规格已同步；README.md 与 README.zh-CN.md 已分别以英文和中文更新。现有 docs/ 用户及历史文档保留；新增两张无密钥验收截图，无需迁移或删除历史文档。
