## 1. 提炼资格与策略

- [ ] 1.1 在 MemoryExtractionServiceTest 中新增中英文纯活动查询、一次性视频/文档活动、混合长期陈述、伪造来源、高置信错误 auto 的回归用例；验证不合格候选均不写入 active/pending。
- [ ] 1.2 修改 MemoryExtractionService 的内部候选结构和资格校验，验证用户消息 ID、角色、原文片段及长期价值类别；运行步骤 1.1 的测试并保留单个非法候选不阻断其它合法候选的行为。
- [ ] 1.3 更新后端双语 memory.prompt 资源，要求纯活动查询无候选、生成正文与依据说明跟随有效语言；补充 zh/en 交叉来源语言及引用保真测试，验证资源 key/参数一致。
- [ ] 1.4 回归 smart/confirm_all/off、记住/忘记、凭据过滤、LLM 不可用、预算阻断和来源幂等；在 MemoryExtractionServiceTest 与 DesktopMemoryControllerTest 中验证失败不影响聊天、off 仍允许手动管理。

## 2. 去重与兼容

- [ ] 2.1 扩展 MemoryExtractionService 的有界摘要及重复引用校验，纳入 rejected/disabled 抑制参照并保持原输入预算；测试摘要截断、非法 duplicateOf 和敏感内容过滤。
- [ ] 2.2 在 LongTermMemoryServiceTest 与 MemoryExtractionServiceTest 覆盖同义候选复用、中英文同一事实、拒绝/停用项抑制、不同目标不误覆盖及物理删除后解除去重；确认现有规范化正文去重仍通过。
- [ ] 2.3 验证既有 memory.json 无需迁移，语言切换不改写历史正文或手动输入，pending/rejected/disabled 不进入 Agent 上下文；以存储快照与上下文测试作为完成证据。

## 3. 会话记忆入口

- [ ] 3.1 修改 desktop-ui/index.html、chat.js、styles.css，增加顶部记忆入口、默认收起及关闭行为，保留独立业务上下文；Node 测试覆盖首次加载、刷新、会话切换、零条入口及关闭释放宽度。
- [ ] 3.2 在 chat.js 统一全局 pending 计数与可访问列表，处理后台更新、加载错误/重试、过期响应及批准后刷新；Node 测试验证不自动展开、不抢焦点、不重置消息滚动和计数不伪装成零。
- [ ] 3.3 增加候选用途说明、折叠依据与来源会话提示，保留全部已有管理操作；补充 Node 测试验证批准失败、来源缺失、编辑草稿及手动输入在收起/重开后不丢失。
- [ ] 3.4 更新 locales/zh.json、locales/en.json 的入口、计数、用途、状态和依据文案，添加键盘标签及焦点恢复；运行 Node 国际化/交互测试，确认正文不被前端翻译或覆写。

## 4. 综合验收与规格交付

- [ ] 4.1 使用 JDK 21 运行 `mvn -pl self-analyst-app -am '-Dtest=MemoryExtractionServiceTest,LongTermMemoryServiceTest,DesktopMemoryControllerTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`，记录结果；运行 `node --test self-analyst-app/src/test/js/*.test.mjs` 验证会话与国际化集成。
- [ ] 4.2 中英文界面分别验证宽/窄窗口、增强和 fallback 消息面、键盘操作、5 条全局 pending 及异步候选更新，并保存截图；回归工作区已有文档附件功能，用实际模型检查跨语言来源的候选输出和纯活动查询不新增记忆。
- [ ] 4.3 若实施涉及跨模块行为，运行 `mvn test`；运行 `openspec validate refine-chat-memory-experience --strict`，记录所有验证结果和实际限制。
- [ ] 4.4 使用 openspec-sync-specs 同步 long-term-memory 与 desktop-chat 主规格，更新 docs 下相关用户说明，写明语言生效及历史记忆保留边界；验证主规格严格校验通过。
- [ ] 4.5 完成前述任务后使用 openspec-archive-change 归档，检查归档产物及任务状态；若获授权交付，提交注明 change 和验证结果，通过功能分支创建 PR，等待最新提交包括 Windows 全量验证在内的必需检查通过后按授权合并。
