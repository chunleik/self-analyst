# 验证记录

## 实现

- 会话列表删除入口改用应用内 `dialog`，居中、遮罩、双语明确文案、红色删除按钮，默认聚焦取消。
- 取消及 Esc 不删除；异步确认捕获原会话 ID，并在请求完成前阻止重复删除操作。
- 关闭和列表重绘后恢复焦点。原入口从仅悬停显示改为悬停或键盘聚焦时显示，使取消后的焦点实际可见。

## 自动验证

- 新增 7 项行为测试：取消与 Esc、重复确认及连续打开、键盘循环、真实列表删除入口、目标绑定及请求去重、失败保留缓存、触发元素替换后的焦点回退。
- 针对性弹窗、会话路由、渲染、样式和国际化测试：54 项通过。
- 最终 `node --test self-analyst-app/src/test/js/*.test.mjs`：197 项通过、0 失败。
- `openspec validate center-session-delete-confirmation --strict`：通过。
- `openspec validate --specs --strict`：30 项通过、0 失败。
- `git diff --check`：通过。

## 浏览器验收

使用 `node self-analyst-app/src/test/js/session-delete-review-server.mjs` 提供的 loopback 示例页面。该页面加载正式 UI 资源，删除仅修改内存中的示例会话，不连接真实后端。

- 中文桌面视口 1280×800：弹窗 420×165.20，中心 (640, 400)，位于应用视口中央。
- 中文窄视口 319×764：弹窗宽 287，左右各留 16px，正文换行、按钮可操作。
- 英文视口由 1280×800 调整到 360×640：弹窗变为 328×186.20，中心 (180, 320)，持续居中。
- 默认焦点为取消；Tab／Shift+Tab 循环于两按钮；Esc 及默认 Enter 关闭后删除计数仍为 0，并恢复原删除入口焦点。
- 点击遮罩未关闭或执行删除。明确点击删除后示例请求计数为 1，目标会话移除，焦点转至剩余活动会话的删除入口。
- 截图和测试日志位于忽略的输出目录 `self-analyst-app/target/session-delete-review/`：`zh-desktop.png`、`en-desktop.png`、`en-narrow.png`、`node-tests.log`。

## 范围与限制

此次为前端资源变更，未运行 Java 全量测试，也未构建或替换已安装桌面应用。浏览器验收使用应用内浏览器，Windows WebView2 打包后的验证尚未执行。主规格已同步；未改动 README 或 `docs/`，现有历史和用户文档继续保留。
