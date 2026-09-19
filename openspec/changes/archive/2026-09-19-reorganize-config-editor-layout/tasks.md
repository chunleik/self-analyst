## 1. 布局与交互

- [x] 1.0 在 `index.html`、`ui.js` 实现三个按需加载页签与共享布局；验证默认模型设置、运行数据懒加载、跨页签草稿确认及焦点。
- [x] 1.1 调整 `self-analyst-app/src/main/resources/desktop-ui/config.js` 的高级配置结构，形成文件头、辅助工具、编辑与运行信息区、底部操作区；保留事件 ID，并以渲染结果确认 DOM 顺序和编辑器可访问名称。
- [x] 1.2 调整 `self-analyst-app/src/main/resources/desktop-ui/config-layout.css`，必要时更新 `index.html` 的活动视图标识，实现受视口约束的尺寸、持续可见操作区和工具换行；以 1280×800、800×600、390×700 浏览器尺寸确认无整体横向溢出且保存按钮可见。
- [x] 1.3 整理 `config.js` 的状态摘要、来源明细和保存反馈，按需补齐现有语言资源；验证轮询不重建编辑器、不丢草稿，错误和待应用状态可见，中文与英文均无缺失翻译键。
- [x] 1.4 重排 `llm-settings.js` 模型表单，固定保存栏，增加新密钥显隐与可点选真实模型候选；通过模型状态与 DOM 交互测试验证保存、放弃、测试和凭据语义。
- [x] 1.5 重构 `runtime-storage.js` 运行数据页，接入真实目录、四类容量、合计、刷新与清理反馈，保留原生命令边界；测试异步过期、加载失败、清理确认和失败重试。

## 2. 验证与收尾

- [x] 2.1 在 `self-analyst-app/src/test/js/` 的配置相关测试中补充必要交互回归，覆盖保存失败保留文本、语言草稿、运行刷新和视图切换；运行 `node --test self-analyst-app/src/test/js/static-config.test.mjs self-analyst-app/src/test/js/config-runtime.test.mjs self-analyst-app/src/test/js/llm-settings.test.mjs` 并记录结果。
- [x] 2.2 运行 `node --test self-analyst-app/src/test/js/*.test.mjs`；用虚构配置在浏览器验证长路径、长原文、长错误、只读失败、键盘焦点及模型设置和文件设置布局，保留截图和验证记录作为完成证据。
- [x] 2.3 同步 README.md 和 README.zh-CN.md 的三个页签说明，保留已有 mockup 作为历史设计参考；使用 openspec-sync-specs 同步 `openspec/specs/user-configuration/spec.md`，运行 change 与主规格严格校验后使用 openspec-archive-change 归档。
