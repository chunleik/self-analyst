# Agent 提示词资源

本目录保存 `AgentPrompts` 使用的内置提示词。文件随 Maven 构建进入应用 JAR，方便开发期间直接编辑；修改后必须重新构建并重启 SelfAnalyst 才会生效，不支持运行时热加载。

提示词文件必须使用无 BOM 的合法 UTF-8 编码。`*.zh.md` 是中文版本，`*.en.md` 是英文版本；两种语言由开发者分别维护，不在运行时翻译。提示词正文不要加入说明性 front matter 或维护备注，开发说明统一写在本文件中。加载器会移除编辑器在文件末尾保留的一个换行符；如确实需要模型看到结尾空行，应在文件末尾保留两个换行符。

## 文档用途与调用时机

| 文档 | 用途 | 调用时机 |
| --- | --- | --- |
| `README.md` | 说明本目录全部提示词资源、占位符和验证方式，不属于模型提示词 | 仅供开发人员阅读，不会由 `PromptResources` 加载或发送给模型 |
| `system.zh.md` | 中文主 Agent 的身份、使命、工作流程、隐私规则、时间规则和能力片段占位符 | `SelfAnalystAgent` 初始化并构造 `ReActAgent` 时装配一次 |
| `system.en.md` | 主 Agent 系统提示的英文版本 | 有效语言为英文时，在 Agent 初始化阶段装配一次 |
| `memory-context.zh.md` | 将最新长期记忆包装为中文临时系统上下文 | 每次主 Agent 调用前，由 `DynamicMemoryContextMiddleware` 读取最新记忆并追加 |
| `memory-context.en.md` | 长期记忆临时上下文的英文版本 | 每次英文主 Agent 调用前追加 |
| `memory-empty.zh.md` | 中文模式下没有长期记忆时的占位文案 | `memory-context.zh.md` 渲染时发现记忆为空时使用 |
| `memory-empty.en.md` | 没有长期记忆时的英文占位文案 | `memory-context.en.md` 渲染时发现记忆为空时使用 |
| `plain-completion.zh.md` | 中文无状态摘要改写器的系统提示，禁止工具调用和会话记忆 | 每次调用 `SelfAnalystAgent.completePlain()` 时使用，包括 Wiki 摘要和文件摘要 |
| `plain-completion.en.md` | 无状态摘要改写器的英文版本 | 英文模式下每次调用 `completePlain()` 时使用 |
| `wiki-enabled.zh.md` | 告诉 Agent 可以使用 WikiTools 查询历史活动摘要 | Agent 初始化时检测到 Wiki 可用后插入主系统提示 |
| `wiki-enabled.en.md` | WikiTools 可用说明的英文版本 | 英文 Agent 初始化且 Wiki 可用时插入 |
| `wiki-disabled.zh.md` | 明确告知 Agent 当前 Wiki 不可用 | Agent 初始化时检测到 Wiki 未启用或初始化失败后插入 |
| `wiki-disabled.en.md` | Wiki 不可用说明的英文版本 | 英文 Agent 初始化且 Wiki 不可用时插入 |
| `wiki-semantic.zh.md` | 说明没有明确时间范围时优先使用 Wiki 语义检索 | Wiki 和语义索引同时可用时，填入 `wiki-enabled.zh.md` |
| `wiki-semantic.en.md` | Wiki 语义检索说明的英文版本 | 英文模式下 Wiki 语义索引可用时填入 |
| `file-tools.zh.md` | 说明文件搜索、最近文件、文件摘要和索引状态工具 | Agent 初始化时检测到 FileTools 已注册后插入 |
| `file-tools.en.md` | 文件工具说明的英文版本 | 英文 Agent 初始化且 FileTools 可用时插入 |
| `web-search.zh.md` | 说明何时使用联网搜索，以及个人活动数据仍优先使用本地工具 | 每次主系统提示初始化时固定插入 |
| `web-search.en.md` | 联网搜索说明的英文版本 | 英文主系统提示初始化时固定插入 |
| `config-tools.zh.md` | 说明读取和修改 SelfAnalyst 配置的方式及重启要求 | Agent 初始化时检测到 ConfigTools 可用后插入 |
| `config-tools.en.md` | 配置工具说明的英文版本 | 英文 Agent 初始化且 ConfigTools 可用时插入 |

## 占位符契约

`system.*.md` 必须且只能使用以下占位符：

- `{{current_time}}`：按有效语言格式化后的当前本地时间。
- `{{initial_memory_summary}}`：Agent 初始化时传入的记忆摘要；当前主调用传入空字符串。
- `{{wiki_context}}`：Wiki 已启用或未启用片段。
- `{{file_tools_context}}`：FileTools 可用时的说明，否则为空。
- `{{web_search_context}}`：固定插入的联网搜索说明。
- `{{config_tools_context}}`：ConfigTools 可用时的说明，否则为空。

`wiki-enabled.*.md` 使用 `{{wiki_semantic_context}}`，语义索引不可用时替换为空字符串。`memory-context.*.md` 使用 `{{memory_summary}}`。

资源渲染器会严格校验占位符集合。缺少占位符值、多传值、占位符语法畸形、资源不存在、资源名非法或文件不是合法 UTF-8 时，Agent 初始化或调用会立即失败，避免把未完成或损坏的模板发送给模型。`{{` 和 `}}` 是保留定界符，正文不要将它们用作普通文本。

## 修改与验证

修改中文提示时应同步检查英文版本，反之亦然。完成编辑后运行：

```powershell
mvn -pl self-analyst-app -am '-Dtest=AgentPromptsTest,PromptResourcesTest,DynamicMemoryContextMiddlewareTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

测试验证资源已进入 classpath、文件非空、占位符完整、中英文隔离，以及 Wiki、文件和配置能力片段按运行时状态正确组合。
