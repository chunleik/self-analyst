## 1. 模型与持久状态适配

- [x] 1.1 核验当前 AgentScope 图片块、模型适配和 AgentState 序列化扩展点，在 agent 测试中加入本地假模型端点；验证 PNG/JPEG 最终 HTTP 请求含真实图像内容，持久状态只保存受管引用。
- [x] 1.2 扩展 SelfAnalystAgent 与 DesktopAgentController 的 canonical 轮次读取及历史导入；测试图文、纯图片、缺失图片、重启追问、terminal 重放以及原 turn 重试无重复。
- [x] 1.3 适配摘要、记忆提取和上下文压缩的图片占位、预算及引用保留；验证压缩失败保留原状态、成功后未压缩图片仍可用于后续请求，base64 不进入文字摘要或日志。
- [x] 1.4 增加视觉不支持错误映射与本地化文案；以假服务商覆盖明确不支持、一般 400、401 和网络故障，验证不会自动丢图重发。

## 2. 图片存储与接口

- [x] 2.1 为 ChatSessionStore 增加可重复 SQLite schema 迁移、图片元数据与有序关联，扩展 user/pending 原子追加；用 @TempDir 测试旧库、跨会话引用、重复引用、PUT 改写拒绝和事务回滚。
- [x] 2.2 新增受管图片服务及 DesktopServer 上传/读取/未关联删除路由；测试 PNG/JPEG、错误 MIME、损坏文件、空文件、字节/像素上限、非法路径以及普通 JSON 仍受 256 KiB 限制。
- [x] 2.3 接入 ChatSessionDeletionCoordinator 与启动恢复，加入 24 小时未关联回收及双历史引用回收；用中断点测试证明无提前删除、删除 intent 恢复和已删除图片不可访问。

## 3. 桌面输入和历史展示

- [x] 3.1 在 desktop-ui 添加共享会话级图片草稿和选择/粘贴/预览/移除逻辑，补充中英文标签；Node 测试覆盖数量大小校验、移除、对象 URL 释放及会话切换。
- [x] 3.2 扩展 api.js、发送流程、deep-chat-adapter.js 与原生 fallback，支持上传后原子追加、纯图片发送、失败草稿恢复及同轮次重试；Node 测试覆盖晚到响应、上传部分失败、追加失败和持久化后重试。
- [x] 3.3 两种消息面按 canonical images 渲染安全缩略图与不可用状态；验证刷新、重启、窄窗口、键盘操作、组件降级和原有生成文档卡片不受影响，保留 UI 截图证据。

## 4. 综合验证与文档

- [x] 4.1 使用本地假视觉服务端运行完整流程：选择或粘贴 → 发送 → 请求内容验证 → 重启追问 → 切换模型重试 → 删除；记录两种消息面的结果。
- [x] 4.2 更新 README.md 英文及 README.zh-CN.md 中文用法、格式限制和图片发送目标说明，并更新必要用户文档；检查双语完整差异信息一致。
- [x] 4.3 在 JDK 21 下先运行新增 Java/Node 针对性测试，再运行 mvn test；记录命令与结果，修复相关回归。
- [x] 4.4 使用 openspec-sync-specs 同步主规格，执行 openspec validate add-chat-image-input --strict 与主规格严格校验；任务完成后使用 openspec-archive-change 归档并记录验证结果。
