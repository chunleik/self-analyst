## 1. 会话文件呈现

- [x] 1.1 在 desktop-ui/documents.js 中提取共用卡片和轮次关联逻辑，保留分页合并、状态刷新及保存反馈；以 Node 测试覆盖去重、跨页刷新、旧会话响应和保存失败重试。
- [x] 1.2 在 desktop-ui 的增强消息面与 fallback 渲染路径中挂载对应轮次附件；以 Node 测试验证多轮、多文件、流式回复、回答失败及刷新恢复均正确归属且不重复。
- [x] 1.3 调整 desktop-ui 布局、样式和国际化文案，移除右侧文档区并增加会话内默认收起的成果入口；验证历史分页、空状态、加载失败重试、长文件名和键盘保存操作。

## 2. 验证与规格交付

- [x] 2.1 运行受影响的文档和聊天 Node 测试，再运行 `node --test self-analyst-app/src/test/js/*.test.mjs`；确认完整 UI 套件通过并记录结果。
- [x] 2.2 验证正常宽度与窄窗口下两种消息面的卡片位置和另存为/下载行为，保留截图或人工验证证据。
- [x] 2.3 同步 openspec/specs/desktop-chat/spec.md，更新现有相关用户说明，执行 `openspec validate move-document-cards-into-chat --strict`；任务全部完成且验证通过后归档该 change。
