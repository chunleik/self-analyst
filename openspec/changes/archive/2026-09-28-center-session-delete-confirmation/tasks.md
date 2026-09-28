## 1. 居中确认交互

- [x] 1.1 在 `self-analyst-app/src/main/resources/desktop-ui/` 添加删除确认模块并更新 `index.html`、`styles.css`，实现居中 dialog、遮罩、取消默认焦点及标题描述关联；通过新增 `self-analyst-app/src/test/js/session-delete-dialog.test.mjs` 验证打开、取消、Esc、一次性确认和连续打开状态清理。
- [x] 1.2 更新 `chat.js` 删除入口，以打开时捕获的 session ID 等待确认后调用既有删除流程，并实现关闭及列表重渲染后的焦点恢复；通过回归测试验证取消零请求、确认单次请求、目标绑定、删除失败保留会话和焦点回退。
- [x] 1.3 更新 `locales/zh.json`、`locales/en.json` 的标题、正文和按钮文案；运行 `node --test self-analyst-app/src/test/js/i18n-catalog.test.mjs self-analyst-app/src/test/js/i18n.test.mjs` 验证资源完整和国际化行为。

## 2. 验证和规格交付

- [x] 2.1 运行新增弹窗测试及 `chat-session-routing.test.mjs`、`chat-render.test.mjs`、`chat-style.test.mjs`，再运行 `node --test self-analyst-app/src/test/js/*.test.mjs` 验证桌面 UI 集成行为，记录结果。
- [x] 2.2 使用无真实用户数据的浏览器测试页面检查中英文、常规及窄窗口、窗口缩放时的居中布局，验证 Tab、Shift+Tab、默认 Enter、Esc、遮罩以及确认后的焦点；保存截图和观察结果作为视觉证据。
- [x] 2.3 使用 openspec-sync-specs 同步 `openspec/specs/desktop-chat/spec.md`，运行 `openspec validate center-session-delete-confirmation --strict` 和 `openspec validate --specs --strict`；确认全部任务完成和验证通过后使用 openspec-archive-change 归档。
