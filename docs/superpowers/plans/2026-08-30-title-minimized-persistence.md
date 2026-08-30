# 标题最小化持久化改造方案

> 状态：已实现
> 编写日期：2026-08-30
> 适用范围：窗口/UIA 内容采集、ActivityWatch 内容事件、Wiki 消费链路、文件索引持久化约束
> 本期非目标：OCR 子系统、音频采集与转写子系统

## 1. 背景

SelfAnalyst 当前把窗口内容采集定义为 UIA/OCR 文本采集。采集器会查询活动窗口的可访问性树，将递归拼接后的文本写入 ActivityWatch 内容事件的 `text_content` 字段；Wiki 再从这些事件中选取屏幕内容片段生成摘要。

新的隐私边界允许采集器在识别阶段临时查询完整内容，但禁止把原始完整内容写入数据库、索引、日志、缓存文件或其他持久化介质。持久化层应以标题为核心，只保存系统窗口标题、应用内上下文标题以及不包含原文的派生结果。

本次改造不是限制 UIA 查询能力，而是建立明确的“读取域”和“持久化域”边界：

```text
活动窗口
  → 临时查询域：完整 UIA 树/扁平文本，可用于标题识别
  → 标题提取域：生成一个受约束的标题候选
  → 持久化域：只允许标题、来源、置信度、应用和时间元数据
```

## 2. 决策与术语

### 2.1 核心决策

1. 允许在内存中读取、遍历和分析完整 UIA 内容。
2. 允许把完整内容临时传给纯内存标题提取逻辑。
3. 原始 UIA 文本不得进入 ActivityWatch、Wiki 数据库、Lucene 索引、日志或其他持久化介质。
4. 内容事件必须采用字段白名单，不得依靠调用方“自觉不传正文”。
5. Wiki 只能消费标题事件和既有派生摘要，不能再消费 `text_content`。
6. 文件模块允许临时读取文件正文并发送给摘要器；最终仅保存路径、校验信息、摘要、主题和向量等派生结果。
7. OCR 和音频本期不改，相关风险留待独立方案处理。

### 2.2 术语

| 术语 | 定义 |
|------|------|
| 系统窗口标题 | 操作系统窗口管理接口返回的标题，例如浏览器标签标题或普通窗口标题 |
| 上下文标题 | 应用内部实际活动对象的标题，例如微信对话人、群名、文章标题或编辑器当前文档名 |
| 原始内容 | UIA 节点的完整 `name/value` 集合、扁平化窗口文本、消息正文、文章正文、代码正文等 |
| 派生结果 | 从原始内容计算出的单一标题、摘要、主题、用途、置信度或统计信息 |
| 临时查询域 | 仅存在于当前采集调用栈或受控内存对象中的原始数据 |
| 持久化域 | SQLite、Lucene、日志、样本文件、备份、WAL、崩溃转储等可跨进程生命周期保留的数据 |

## 3. 范围

### 3.1 本期必须修改

- `self-analyst-content` 的持久化数据模型和 heartbeat 字段。
- `self-analyst-aw` 对内容 bucket 的服务端字段约束。
- `self-analyst-wiki` 对内容事件的读取、事实模型和 LLM prompt。
- 已有 ActivityWatch 内容事件的历史数据净化。
- 内容采集相关配置名称、状态字段、说明文字和兼容策略。
- 单元测试、集成测试和隐私回归测试。
- `docs/specs/content.md`、`docs/specs/llm-wiki.md`、`docs/specs/core.md`、`PRIVACY.md` 和 `README.md` 的最终契约同步。

### 3.2 本期明确不修改

- `OcrEngine`、`ContentCapture` 的 OCR 识别算法、标题条裁剪策略和 OCR 缓存。
- `OcrSampleStore` 及 OCR 原始样本保存行为。
- `AudioWatcher`、`AudioCaptureManager`、本地 Whisper、云端 ASR、音频事件和音频 API。
- 聊天会话正文存储；它属于用户主动创建的会话数据，不属于活动采集事件。
- 文件摘要算法、文件正文临时读取和发送给用户配置的 LLM 的行为。

说明：移除内容 heartbeat 的 `text_content` 会使 OCR 识别出的原始文本不再通过该字段进入 AW，但这只是统一持久化边界的结果，不代表本期修改 OCR 内部行为。OCR 原始样本和音频转写的持久化风险继续存在，必须在后续独立方案中处理。

## 4. 当前实现与问题定位

### 4.1 内容采集链路

当前链路如下：

```text
AxSidecar → UiaTreeWalker.walk
  → 完整 UiaNode 树 + 扁平 uiaText
  → ContentCapture.capture
  → ContentResult.textContent
  → ContentWatcher.heartbeatData
  → data.text_content
  → EventStore.events.datastr
```

允许保留前四步的临时查询和标题识别能力，但 `ContentResult.textContent` 跨越了持久化边界，最终由 `EventStore` 原样写入 `datastr`，必须改造。

### 4.2 微信上下文标题

现有 `ContextTitleExtractor` 能从完整 UIA 文本中识别部分微信对话标题，这是允许的读取行为。但它存在两个问题：

1. 提取成功后，调用方仍同时保存完整 `text_content`。
2. 目前只定义了聊天标题锚点，未形成微信文章标题、公众号文章、内置阅读页等上下文类型的稳定契约。

### 4.3 Wiki 链路

`WikiFactBuilder` 当前从内容事件读取 `text_content`，按应用和窗口分组后选择最长片段；`WikiSummarizer` 把这些片段放入“屏幕内容片段” prompt。新内容事件不再提供该字段，因此 Wiki 必须改为使用系统窗口标题和上下文标题。

### 4.4 文件链路

`FileIndexWorker` 会临时读取文件正文并交给 `FileSummarizer`，但 `file-watch.db` 只持久化路径、大小、哈希、摘要、主题、模型和状态；`FileEmbeddingWorker` 只向量化相对路径、摘要和主题。按本方案的边界，文件链路当前合规，不需要改变功能，只需补充防回归测试和文档约束。

## 5. 目标数据契约

### 5.1 内容事件 v2

内容 bucket 保持 `aw-watcher-content_{hostname}` 命名，以避免不必要的数据源和时间线迁移。新事件在 `data` 中采用以下白名单：

| 字段 | 类型 | 必需 | 说明 |
|------|------|------|------|
| `schema_version` | integer | 是 | 固定为 `2` |
| `app` | string | 是 | 进程或应用标识 |
| `title` | string | 是 | 系统窗口标题，可为空字符串 |
| `context_title` | string | 否 | 识别出的应用内标题 |
| `context_kind` | string | 否 | `chat`、`article`、`document`、`page` 或 `unknown` |
| `title_source` | string | 是 | `window`、`uia_document`、`uia_context` 或 `ocr_title` |
| `title_confidence` | string | 否 | `high`、`medium` 或 `low` |
| `uia_chars` | integer | 否 | 仅用于诊断的字符数量，不包含原文 |
| `ocr_chars` | integer | 否 | 仅用于诊断的字符数量，不包含原文 |

禁止字段包括但不限于：

- `text_content`
- `uia_text`
- `ocr_text`
- `raw_text`
- `raw_tree`
- `content`
- `body`
- 任意屏幕截图、Base64 图片或可还原正文的节点列表

示例：

```json
{
  "timestamp": "2026-08-30T10:00:00Z",
  "duration": 2.0,
  "data": {
    "schema_version": 2,
    "app": "Weixin.exe",
    "title": "微信",
    "context_title": "项目讨论群",
    "context_kind": "chat",
    "title_source": "uia_context",
    "title_confidence": "high",
    "uia_chars": 4280
  }
}
```

### 5.2 标题值约束

- `context_title` 必须为单行文本，去除首尾空白并折叠内部连续空白。
- 最大长度为 200 个 Unicode code point；超过限制的候选应拒绝，不得简单截取正文前 200 字作为标题。
- 不得接受密码占位符、纯 URL、通用应用名、控件名、正文段落或包含明显多句结构的候选。
- 当没有可靠上下文标题时，应省略 `context_title`，不能使用完整 UIA 文本回退。
- `title_source=window` 时，`context_title` 应省略，消费者直接使用 `title`。

### 5.3 标题选择优先级

建议采用以下稳定优先级：

1. 应用专用上下文标题，例如微信对话人、群名或文章标题。
2. UIA `Document.Name` 等明确的页面/文档标题。
3. OCR 标题条识别出的标题候选；OCR 内部行为本期不改。
4. 系统窗口标题。

标题提取可以读取完整 UIA 树和扁平文本，但每次只能向持久化边界输出一个结构化候选，不得输出候选来源的原始上下文。

## 6. 组件修改要求

### 6.1 `self-analyst-content`

#### 6.1.1 分离临时观察结果和可持久化结果

当前 `ContentResult` 同时承载原始文本和最终事件字段，容易把临时内容直接传入 heartbeat。应拆分职责：

- 临时观察对象只在采集包内部使用，可包含 UIA 树、UIA 文本和 OCR 文本。
- 可持久化对象只包含第 5.1 节白名单字段。
- 可持久化对象的类型定义中不得存在任意正文属性，从编译期阻止误传。

可以将最终对象命名为 `TitleCaptureResult`、`ContextTitleResult` 或其他能体现标题语义的名称。具体命名由实现决定，但必须保证原始文本不能通过 getter 或通用 `Map` 进入 `ContentWatcher`。

#### 6.1.2 `ContentWatcher`

- `Snapshot` 只能缓存可持久化标题结果，不能缓存原始文本结果。
- `heartbeatData` 必须按固定白名单逐字段构造。
- 删除 `text_content` 写入。
- 增加 `schema_version=2`。
- 微信窗口的 `context_title` 发生变化时，即使系统窗口标题不变，也必须产生新的 heartbeat 数据，从而使 AW 正确分隔会话。
- 禁止把未知扩展字段透传给 heartbeat。

#### 6.1.3 `ContentEvent`

- 删除 `textContent` 字段和兼容构造器。
- 将事件模型改为标题事件模型，或明确标注为 v2。
- `toHeartbeatData` 只能返回不可变白名单 Map。
- 不保留同时支持 v1/v2 写入的兼容分支；兼容只发生在历史数据读取和迁移阶段。

#### 6.1.4 `ContextTitleExtractor`

- 将返回值从裸字符串扩展为结构化候选，至少包含 `value`、`kind`、`source` 和 `confidence`。
- 保留现有微信聊天标题识别能力。
- 增加微信文章/阅读页标题识别契约：优先使用明确的 `Document.Name`、页面标题控件或应用专用标题节点；正文第一行不能直接视为文章标题。
- 所有候选必须通过统一验证器，拒绝通用标签、过长文本、正文段落和不稳定值。
- 提取器不得记录输入全文、候选附近正文或完整节点 JSON。

#### 6.1.5 `ContentCapture`

- UIA/OCR 临时内容仍可参与标题提取。
- 非 thin 窗口不再把 `uiaText` 作为最终结果。
- OCR/UIA 合并结果不得跨越标题提取边界。
- 保留 `uiaChars`、`ocrChars` 等数值诊断信息。
- OCR 算法、截图策略和样本保存本期不修改。

### 6.2 `self-analyst-aw`

仅在采集端删除字段不足以形成可靠边界。AW 服务端必须对内容 bucket 增加第二道防线。当前至少存在三类写入路径：`HeartbeatController` 调用 `insertHeartbeat`、`EventController` 调用 `insertEvent`，以及 `DataImporter` 或内部组件直接调用 `EventStore`。只在 HTTP heartbeat 控制器校验会留下旁路。

#### 6.2.1 内容 bucket 入库策略

- 当 bucket ID 以 `aw-watcher-content_` 开头，或 bucket `client=aw-watcher-content` 时，服务端必须应用内容事件 v2 白名单。
- 对 `text_content` 等明确禁止字段应拒绝请求，返回 4xx 和不包含原文的错误信息。
- 对未知字段建议拒绝，而不是静默保存，以便尽早发现采集器版本不一致。
- 日志只能记录 bucket ID、禁止字段名和请求结果，不得记录字段值或完整请求体。
- 其他 bucket 的通用事件格式保持不变，避免影响本期排除的音频链路。
- 校验应由共享的内容事件策略承担，并覆盖 `insertEvent`、`insertHeartbeat`、批量事件写入和数据导入；不能分别复制多套字段列表。
- 共享策略必须按 bucket 条件启用，不能在通用事件模型上全局禁用 `text`、`content` 等名称。
- HTTP 控制器应把字段策略异常映射为 `400` 或 `422`，其他解析/存储错误仍按现有错误策略处理；响应不得回显字段值。

#### 6.2.2 版本兼容

- 新采集器只写 v2。
- 新服务端不得接受内容事件 v1 的 `text_content`。
- 历史 v1 数据由一次性迁移处理，不能通过继续接受 v1 写入来实现兼容。
- 如果旧采集器连接新服务端，服务端应拒绝违规 heartbeat；状态页应能表现为 degraded，而不是静默丢失。

### 6.3 `self-analyst-wiki`

#### 6.3.1 `WikiFactBuilder`

- 删除 `sampleContent` 及对 `text_content` 的读取。
- 将 `contentSamples` 改为 `contextTitleSamples` 或语义等价字段。
- 上下文标题采样应优先使用 `context_title`，为空时回退到系统 `title`。
- 去重键建议使用 `(app, effectiveTitle, contextKind)`。
- 同一标题只保留一次，避免 2 秒 heartbeat 在 prompt 中重复。
- 仍需遵守总字符预算，但预算对象变为标题，而非正文片段。

#### 6.3.2 `WikiSummarizer`

- 删除“屏幕内容片段” prompt 段落。
- 新增“应用内标题样本”或“活动标题样本”段落。
- 删除针对屏幕正文、密码和 Token 的 prompt 级补救规则；隐私边界应在入库前保证，不能依赖 LLM 脱敏。
- bump prompt version，例如从 `wiki-v2` 更新为 `wiki-v3`，避免缓存或重试混用旧语义。
- Wiki 最终仍可保存摘要、主要任务、任务片段和脱敏证据；这些属于派生结果。

#### 6.3.3 Wiki 历史数据

- `llm-wiki.db` 当前不保存原始 `text_content`，无需删除既有摘要。
- 不要求重建既有 Wiki 条目。
- 新生成条目必须只使用标题事实。
- Wiki Lucene 索引继续只索引摘要和任务片段，不得读取历史 AW 原始正文。

### 6.4 `self-analyst-file`

本期不修改生产逻辑，但必须固化以下契约：

- `file-watch.db` 不得新增原始正文列。
- `FileRecord` 不得新增正文、截断正文或 prompt 字段。
- `FileSemanticIndex` 只能索引路径、摘要、主题和其他派生字段。
- 日志不得输出文件正文或完整 LLM prompt。
- 失败信息不得包含正文片段。

建议增加 schema 和字节级测试，证明唯一正文副本只存在于当前处理调用的内存中。

### 6.5 配置和桌面状态

当前 `aw.collection.content` 名称容易让用户理解为正文采集。实现最终选择保留旧键以避免破坏
现有用户配置，并把语义改为“上下文标题识别”；未引入第二套配置键。下表中的新键方案不再采用：

| 当前键 | 目标键 | 策略 |
|--------|--------|------|
| `aw.collection.content` | `aw.collection.contextTitle` | 读取旧键作为一次性兼容输入，写回新键并标记旧键废弃 |
| `aw.collection.content.pollMs` | `aw.collection.contextTitle.pollMs` | 同上 |

要求：

- 默认值可以继续为 `true`，因为新语义只保存标题。
- 配置说明改为“是否识别并保存活动窗口的上下文标题”。
- 桌面 UI 的“内容采集”改为“上下文标题识别”。
- `/desktop/status.collectors.content` 可在一个兼容周期内保留，同时新增 `contextTitle`；前端优先读取新字段。
- 后续删除旧状态字段前必须确认桌面端和集成测试已完成迁移。

本次实现采用保留旧键的方案，并已同步用户可见说明；状态 API 新增 `contextTitle`，同时保留
`content` 作为兼容别名。后续若重命名配置键，应另立迁移规格，不能同时长期维护两套开关。

## 7. 历史数据迁移

### 7.1 迁移目标

净化所有 `aw-watcher-content_*` bucket 中的历史事件：

1. 读取 `datastr` JSON。
2. 在删除原文前，使用现有或新版标题提取器尝试补齐 `context_title`。
3. 仅保留 v2 白名单字段。
4. 写入 `schema_version=2`。
5. 删除 `text_content` 和所有未知内容字段。

### 7.2 启动顺序

迁移必须发生在内容 watcher 启动之前：

```text
启动嵌入式 AW
  → 打开数据存储
  → 执行内容事件 v2 迁移
  → 注册内容 bucket 入库策略
  → 启动 Window/AFK/ContextTitle watcher
  → 启动 Wiki worker
```

迁移失败时：

- 不得启动内容 watcher 和 Wiki worker。
- 后端其他功能可以降级运行。
- 状态页应显示迁移失败，并给出不含原文的错误摘要。
- 下次启动必须继续重试，迁移应保持幂等。

### 7.3 SQLite 残留处理

仅执行 `UPDATE events SET datastr=...` 不能保证原文从物理文件消失，旧值可能残留在 WAL、空闲页或临时文件中。迁移成功后必须：

1. 提交每个 bucket 的净化事务。
2. 执行 WAL checkpoint，并截断 WAL。
3. 在不处于事务时执行 `VACUUM` 或等价的安全重写。
4. 确认 `.db-wal`、`.db-shm` 和迁移临时文件不包含测试原文标记。
5. 不创建包含原始 `text_content` 的普通备份。
6. 删除旧版内容 bucket 独立数据库、`aw.db.pre-legacy-migration-*` 明文备份和完成后残留的迁移工作文件。

如果必须提供恢复能力，应在产品层另行设计由用户明确授权的加密备份；本次迁移默认不复制原始数据库。

### 7.4 迁移标记

- 为迁移定义独立、可查询的版本标记，例如 `content_event_schema=2`。
- 标记只能在所有内容 bucket 完成净化、checkpoint 和重写后提交。
- 已完成迁移的数据库再次启动时不得重复重写。
- 新发现的旧格式 bucket 仍必须单独净化，不能只依赖全局标记跳过。

### 7.5 降级和回滚

旧版本程序会重新写入 `text_content`，因此完成迁移后的数据目录不支持无保护降级。发布说明必须明确：

- 回滚旧程序会恢复违规写入。
- 如确需回滚，必须关闭内容采集开关。
- 不得通过恢复含原文的自动备份完成回滚。

## 8. 日志、错误和可观测性

- 可以记录 UIA 节点数、字符数、提取耗时、标题候选是否命中和拒绝原因枚举。
- 不得记录 UIA 全文、节点 JSON、标题附近正文或 heartbeat 完整请求体。
- 标题可以出现在正常产品功能中，但调试日志默认不应打印具体标题，避免日志形成第二数据副本。
- 迁移日志只记录 bucket ID、扫描事件数、净化事件数、失败数和耗时。
- 禁止字段被服务端拒绝时，只记录字段名，不记录字段值。

建议新增指标：

- `context_title_query_count`
- `context_title_hit_count`
- `context_title_reject_count{reason}`
- `content_event_policy_reject_count{field}`
- `content_event_migration_sanitized_count`

## 9. 测试方案

### 9.1 `self-analyst-content` 单元测试

| 测试 | 预期 |
|------|------|
| 普通窗口只有系统标题 | v2 事件只含 `app/title/title_source`，不含正文字段 |
| 微信同一 HWND 切换对话 | 两次结果的 `context_title` 不同，均不包含消息正文 |
| 微信聊天 UIA 含唯一秘密标记 | 可识别对话标题，但 heartbeat Map 和序列化 JSON 不含秘密标记 |
| 微信文章页 | 识别文章标题并标记 `context_kind=article` |
| 提取失败 | 省略 `context_title`，不得把 UIA 文本作为回退值 |
| 超长/多行候选 | 拒绝候选，不进行正文截断式持久化 |
| 构造事件时传入未知字段 | 类型系统无法表达，或白名单序列化拒绝 |

### 9.2 `self-analyst-aw` 单元测试

| 测试 | 预期 |
|------|------|
| 内容 bucket 提交合法 v2 事件 | 正常入库 |
| 内容 bucket 提交 `text_content` | 返回 4xx，数据库无新事件 |
| 内容 bucket 提交未知字段 | 按最终策略拒绝，日志不含字段值 |
| 通过批量 events 接口提交正文 | 整批原子拒绝，不得部分写入 |
| 通过数据导入路径写入旧内容事件 | 进入显式迁移流程或被拒绝，不能绕过策略 |
| 非内容 bucket 提交现有事件 | 行为不变，确保音频等本期非目标不回归 |

### 9.3 Wiki 单元测试

| 测试 | 预期 |
|------|------|
| 同时存在 `context_title` 和 `title` | 使用 `context_title` |
| 只有 `title` | 回退使用系统窗口标题 |
| 历史事件带 `text_content` | 新事实构建器忽略该字段 |
| 构建 prompt | 不出现“屏幕内容片段”、秘密正文标记或 `text_content` |
| 语义索引 | 只包含摘要、任务片段和标题派生内容 |

### 9.4 文件模块回归测试

| 测试 | 预期 |
|------|------|
| 使用带唯一秘密标记的文件完成索引 | `file-watch.db` schema 和数据行不含原始正文 |
| 完成语义索引 | Lucene 存储字段不含原始正文 |
| 摘要失败 | `last_error` 和日志不含正文或 prompt |

### 9.5 迁移测试

建立包含以下数据的真实 SQLite fixture：

- 普通窗口完整 UIA 文本。
- 微信聊天正文和可提取的对话标题。
- 多个内容 bucket。
- WAL 中仍含旧 `text_content` 的场景。
- 已部分迁移、进程中断后重启的场景。

验证：

1. 迁移后所有事件符合 v2 白名单。
2. 可提取的上下文标题得到保留。
3. 迁移重复运行结果不变。
4. 对数据库、WAL、SHM 和临时文件执行字节扫描，唯一秘密标记不存在。
5. 迁移失败时内容 watcher 未启动。

### 9.6 集成验收

建议新增跨模块测试，构造带秘密标记的假 UIA 树：

```text
系统标题：微信
上下文标题：项目讨论群
消息正文：SELF_ANALYST_FORBIDDEN_BODY_7F3A
```

运行一次完整采集、heartbeat、Wiki 摘要和语义索引后，检查所有持久化目录：

- AW 数据库只包含“微信”和“项目讨论群”。
- Wiki 数据库和索引可包含基于标题生成的摘要，但不得包含秘密标记。
- 文件模块数据库无变化。
- OCR 和音频不新增本期隐私验收项；既有回归测试仍须通过。

## 10. 实施顺序

为避免新旧组件之间出现短暂正文泄漏，建议按以下顺序开发和合并：

1. 定义内容事件 v2 白名单、标题候选模型和测试 fixture。
2. 修改标题提取结果与 `ContentWatcher`，停止产生 `text_content`。
3. 在 AW 服务端增加内容 bucket 入库策略。
4. 修改 Wiki facts 和 prompt，使其只消费标题。
5. 实现历史 AW 数据迁移及物理净化。
6. 调整启动顺序，确保迁移早于 watcher 和 Wiki worker。
7. 处理配置键、桌面状态和用户可见文案。
8. 补齐文件持久化防回归测试。
9. 同步正式规格、README 和隐私说明。
10. 运行模块测试、跨模块测试及完整 `mvn test`。

不建议先修改 Wiki 再修改采集端，因为旧采集端仍会继续向数据库写入正文；也不建议先执行历史净化再部署新写入策略，否则旧 watcher 会立即重新产生 `text_content`。

## 11. 预计文件影响清单

### 11.1 生产代码

| 模块 | 文件 | 预期修改 |
|------|------|----------|
| content | `ContentEvent.java` | 删除正文属性，改为 v2 标题事件 |
| content | `ContentWatcher.java` | heartbeat 白名单、schema version、上下文标题变化处理 |
| content | `capture/ContentResult.java` | 拆分临时内容和可持久化标题结果 |
| content | `capture/ContentCapture.java` | 原始文本仅用于标题提取，不再作为最终结果 |
| content | `capture/ContextTitleExtractor.java` | 结构化候选、微信文章标题和统一验证 |
| aw | `HeartbeatController.java` | 将内容策略异常映射为安全的 4xx 响应 |
| aw | `EventController.java` | 批量事件写入使用同一内容策略并保持原子性 |
| aw | `EventStore.java` | 在共享写入边界按 bucket 条件执行内容事件策略 |
| aw | `DataImporter.java` | 旧内容事件只能进入显式迁移，不得直接绕过策略 |
| aw | 新增迁移组件 | 净化历史内容事件和 SQLite 物理残留 |
| app | `AppSession.java` | 迁移早于 watcher/Wiki 启动，处理失败降级 |
| app | `DesktopStatusController.java` | 上下文标题识别及迁移状态 |
| app | `SupportedKeys.java`、`Config.java` | 配置语义和可选键迁移 |
| wiki | `WikiFactBuilder.java` | 内容片段改为上下文标题样本 |
| wiki | `WikiSummarizer.java` | 标题型 prompt、prompt version bump |
| wiki | `WikiWorker.java` | 空事实判断适配新字段 |

建议新增一个无正文状态的 `ContentEventPolicy`（名称可调整），由 `EventStore.insertEvent` 和 `EventStore.insertHeartbeat` 统一调用。该策略按 bucket ID/元数据选择性启用，因此可以覆盖 HTTP、导入和内部调用，又不会误伤本期排除的音频 bucket。

### 11.2 测试与文档

- `self-analyst-content/src/test/...`
- `self-analyst-aw/src/test/...`
- `self-analyst-wiki/src/test/...`
- `self-analyst-file/src/test/...`
- `self-analyst-integration-test/...`
- `docs/specs/content.md`
- `docs/specs/llm-wiki.md`
- `docs/specs/core.md`
- `docs/specs/integration-test.md`
- `README.md`
- `PRIVACY.md`

## 12. 验收标准

全部满足后才可认为改造完成：

1. 新产生的内容事件中不存在 `text_content` 或等价正文字段。
2. 内容 bucket 服务端拒绝任何正文字段，无法通过旧客户端或手写请求绕过。
3. 微信窗口标题不变时，切换对话能够保存新的对话标题。
4. 微信文章/阅读页能在可靠识别时保存文章标题；无法识别时宁可省略，不能保存正文。
5. Wiki 只使用系统标题、上下文标题和既有派生摘要生成新条目。
6. 历史内容事件完成逻辑净化和 SQLite 物理净化。
7. 唯一秘密标记在 AW DB、WAL、SHM、Wiki DB、Lucene、日志和临时文件中均不存在。
8. `file-watch.db` 和文件语义索引不包含原始文件正文。
9. OCR 和音频生产代码及既有测试没有被本次改造改变。
10. 正式规格和用户文档不再宣称默认持久化窗口正文。

## 13. 后续独立事项

以下问题已知但不属于本期：

- OCR 原始调试样本会持久化完整窗口截图和 OCR 文本。
- `ocr.title-strip-height=0` 允许完整窗口 OCR。
- 音频事件会保存逐段转写，并可通过桌面 API 读取。
- 云端 ASR 会把原始 WAV 发送给外部服务。

后续应分别建立 OCR 持久化最小化方案和音频数据保留方案，不能在本期实现中顺带修改，以免扩大测试范围和发布风险。
