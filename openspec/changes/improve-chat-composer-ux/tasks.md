# Tasks

本清单已获批准并进入实施，代码实现不代表全部验收完成。每项实施结束立即勾选；受环境限制的验收保持未勾选并记录原因。

## 1. 适配能力核对与输入布局

- [x] 1.1 阅读 `AGENTS.md`、本 change 和两个主规格，核验 vendored Deep Chat 的 inputAreaStyle 与消息展示能力与当前 `deep-chat-adapter.js`；记录共享外置 composer 的流式适配通路及 Windows Tauri 拖放事件路径，并在主窗口禁用原生拖放拦截（不新增权限）。若与 design.md 不符，先按 openspec-update-change 更新规划，不修改后端或盲目使用新版本 API。
- [x] 1.2 在 `index.html`、`styles.css`、`deep-chat-adapter.js`、新增 `chat-composer.js` 实现两种消息面的一体式多行输入布局和高度更新；补 `chat-style.test.mjs`、`deep-chat-adapter.test.mjs` 及按需新建 `chat-composer.test.mjs`，覆盖长文本、清空、预填、窗口宽度变化和 fallback，运行 `node --test self-analyst-app/src/test/js/chat-style.test.mjs self-analyst-app/src/test/js/deep-chat-adapter.test.mjs self-analyst-app/src/test/js/chat-composer.test.mjs`。
- [x] 1.3 更新 `README.md`（英文）与 `README.zh-CN.md`（中文）的会话输入说明，写明多行和快捷键；检查两版一致且英文版没有新增中文段落。

## 2. 附件整合与安全输入

- [x] 2.1 将 `chat-images.js` 的添加、缩略图、数量与移除整合至输入容器，移除独立发送图片按钮及常驻原生 file input；选择、粘贴、拖入复用校验路径。补 `chat-images.test.mjs`，覆盖合法图、纯图、混合非法文件、数量/字节/像素上限、目录/URL 拒绝，运行该 Node 测试。
- [x] 2.2 给异步图片解码/清理增加会话与草稿失效保护，保持上传失败草稿和 object URL 回收；在 `chat-images.test.mjs` 覆盖解码中切换、移除、清空、重复添加及增强降级，运行该测试及 `chat-session-routing.test.mjs`。
- [x] 2.3 在 `locales/zh.json`、`locales/en.json` 增加就地限制、失败和附件数量反馈，在 README 两版补充拖入方式与不变限制；验证语言键齐全、文件名安全渲染、选择取消不丢草稿，运行图片及输入区 Node 测试。

## 3. 发送状态、键盘与可访问性

- [x] 3.1 在 `chat.js`、`deep-chat-adapter.js`、必要的 `events.js` 与 `summary-repair.js` 绑定中统一主按钮/Enter 条件，区分准备与模型生成，复用流取消且绑定原 session/turn；在 `chat-composer.test.mjs`、`chat-session-routing.test.mjs`、`deep-chat-adapter.test.mjs` 覆盖重复点击、停止、发送中切换、持久化前后失败、LLM 不可用及 reconciliation，运行三项测试。
- [x] 3.2 完成 IME 组合保护、焦点恢复、图标标签和非颜色状态反馈，中英文全部入 locale；在输入区测试覆盖 composition/Enter/Shift+Enter、Tab、取消选图、移除焦点项、重绘不抢焦点以及重复初始化，运行 `chat-composer.test.mjs` 与 `chat-images.test.mjs`。
- [x] 3.3 README 两版说明准备/停止状态与失败后重试区别；检查未改变“原轮次重试、不重复 user”的说明，英文版保持纯英文。

## 4. 集成验证与交付准备

- [x] 4.1 运行针对性合集：`node --test self-analyst-app/src/test/js/chat-composer.test.mjs self-analyst-app/src/test/js/chat-images.test.mjs self-analyst-app/src/test/js/chat-style.test.mjs self-analyst-app/src/test/js/deep-chat-adapter.test.mjs self-analyst-app/src/test/js/chat-session-routing.test.mjs`，再运行 `mvn test`；记录实际命令和通过/失败摘要，不以提案校验代替运行测试。
- [ ] 4.2 使用 `cd self-analyst-desktop && pnpm install && pnpm tauri dev` 在 Windows Tauri 验证 OS 拖放、真实中文输入法、连续发送、停止/切换、浅深色、中英文及配置允许的最小窗口；保存两种消息面截图与结果。环境不支持时明确标注未运行，不勾选、不以浏览器模拟通过替代。
- [x] 4.3 实施完成后用 openspec-sync-specs 同步 `desktop-chat` 和 `chat-image-input` 主规格，运行 `openspec validate improve-chat-composer-ux --strict`，检查差异只含本 change 实施所需内容，README 语言正确，无敏感数据或无关文件。

## Workflow follow-up

- 用户授权提交/推送后，按 Conventional Commits 提交功能分支并创建 PR，关联本 change、验证结果和 UI 截图。
- 必需检查包含 Windows 全量验证，使用最新提交结果；未经用户授权不合并、不发版、不归档。
