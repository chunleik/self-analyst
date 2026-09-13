## Why

用户查询近期活动时，常驻的长期记忆侧栏占用聊天空间，且把单次观看、浏览文档等临时活动推荐为个人长期特征。候选内容的语言也可能与用户选择的界面语言不一致，使用户难以判断记忆的用途和可信度。

## What Changes

- 修改会话体验：长期记忆默认收起，顶部提供带待确认数量的入口；新增候选只更新数量，不自动展开或抢占焦点。
- 修改提炼规则：纯活动查询不新增记忆；只有明确记忆意图或有用户陈述支持的稳定偏好、长期目标、项目等信息可以形成候选。单次窗口活动和 assistant 自行归纳不能作为长期事实依据。
- 强化候选校验及重复抑制，继续保留 smart/confirm_all/off、凭据过滤和既有批准流程。
- 修改展示：简短说明记忆将用于后续会话，详细依据默认折叠；新生成的候选及依据说明跟随后端有效语言，引用的原始标题、项目名保持原文。
- 保留历史记忆及手动原文，不因语言设置变化批量翻译或修改用户数据；新增内容按语言设置生效后的语言生成。
- 本次为行为改进，不是文档基线迁移。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

- `long-term-memory`：候选资格、结构化来源验证、去重和语言输出、精简的审核展示。
- `desktop-chat`：默认收起的记忆入口、计数、展开关闭和焦点行为。

## Impact

- 后端涉及 MemoryExtractionService、LongTermMemoryService、双语 memory.prompt 资源及相关测试；保留 memory.json 作为唯一事实来源及现有记忆管理 API。
- 前端涉及 desktop-ui/chat.js、index.html、styles.css、locales/zh.json、locales/en.json 及 Node 测试。
- 语言沿用 internationalization 的 zh/en/auto 解析及既有生效时机，不新增语言设置或运行时翻译服务。
- 实施需兼容正在进行的 add-agent-document-generation 对会话页面的修改，保留文档附件和消息发送行为。
- 完成实施后同步主规格和用户文档；本次仅生成规划产物，不修改实现或历史记忆。
