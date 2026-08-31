# 上下文标题最小化持久化规格

> **迁移状态：** 现行行为契约已迁移至 [`content-event-persistence`](../../openspec/specs/content-event-persistence/spec.md)。本文档仅保留为旧 ID、历史背景和源码追溯，不再独立维护。

> 状态：已实现
>
> 规格前缀：`SPEC-CTP-*`
>
> 适用模块：`self-analyst-content`、`self-analyst-aw`、`self-analyst-wiki`、`self-analyst-app`

## 1. 读取域与持久化域

### SPEC-CTP-001：读取边界

标题采集器允许在当前调用内查询完整 UIA 树及文本。原始控件树、正文和中间拼接字符串不得进入
Snapshot、事件、数据库、日志、缓存、普通备份或派生任务队列。调用结束后只保留标题投影。

### SPEC-CTP-002：派生边界

Wiki 可以保存由标题事实生成的聚合指标、摘要和任务片段，但不得重新获得 UIA 原文。派生失败日志
不得包含完整 prompt 或输入正文。

## 2. 内容事件 v2

### SPEC-CTP-010：适用范围

bucket ID 以 `aw-watcher-content_` 开头，或 bucket `client` 为 `aw-watcher-content` 时，所有写入入口
必须应用内容事件 v2 策略。

### SPEC-CTP-011：新事件白名单

| 字段 | 类型 | 必需 | 约束 |
|------|------|------|------|
| `schema_version` | integer | 是 | 固定为 `2` |
| `app` | string | 是 | 单行，最多 260 code point |
| `title` | string | 是 | 系统窗口标题，单行，最多 1024 code point |
| `context_title` | string | 否 | 单行，最多 200 code point |
| `context_kind` | string | 条件必需 | `chat/article/document/page/unknown` |
| `title_source` | string | 是 | 新事件仅 `window/uia_document/uia_context` |
| `title_confidence` | string | 否 | `high/medium/low` |
| `uia_chars` | number | 否 | 非负诊断计数，不含原文 |

`context_kind` 和 `title_confidence` 仅可在 `context_title` 存在时出现。禁止字段包括
`text_content`、`uia_text`、`ocr_text`、`raw_text`、`raw_tree`、`content`、`body`、截图及节点列表。

### SPEC-CTP-012：历史兼容字段

历史 v2 事件中的 `title_source=ocr_title` 与 `ocr_chars` 可以继续被服务端读取和迁移，但当前采集器
不得生成它们。兼容字段只存在于持久化策略/迁移边界，不得重新出现在采集结果类型、配置或 UI。

### SPEC-CTP-013：标题候选

- 应用专用标题优先于已验证应用结构中的 UIA `Document.Name`，后者优先于系统窗口标题；通用
  `Document.Name` 不得直接持久化。
- 微信聊天输出 `context_kind=chat`；微信文章输出 `context_kind=article`。
- 空值、通用应用名、通用控件、纯 URL、多行/多句正文及超长文本不得成为候选。
- 无可靠候选时省略 `context_title`，不得把正文首句作为回退。

## 3. 写入策略

### SPEC-CTP-020：统一校验

策略必须覆盖 heartbeat、单条/批量 events、数据导入和内部 `EventStore` 调用。批量写入先验证整批，
违规时不得部分入库；导入必须在创建 bucket 或事件前预检。

### SPEC-CTP-021：失败响应

违规 HTTP 写入返回 `422`。响应与日志只能指出禁止字段名，不得回显值或完整请求体。旧采集器不能
通过兼容模式继续写 `text_content`。

## 4. 历史数据净化

### SPEC-CTP-030：逻辑迁移

- 启动时扫描内容 bucket 的历史事件，在删除旧正文前可临时提取可靠标题。
- 迁移结果只保留 v2 白名单及明确的历史兼容字段，删除未知字段与原始正文。
- 迁移幂等，并保存事务快照内的高水位，后续只处理更高 ID 的新增旧事件。
- 逻辑净化先写 `needs_compaction`；物理净化失败时不得提交完成标记或推进高水位。

### SPEC-CTP-031：物理净化

- 使用 SQLite `secure_delete`，提交后执行 WAL checkpoint、WAL 截断和 `VACUUM`。
- 默认不得创建包含原文的普通备份。
- 旧内容独立数据库和迁移工作文件必须在严格路径校验后删除。
- 只有逻辑与物理净化均成功，才能写入 `content-events-title-only-v2` 标记。

### SPEC-CTP-032：启动顺序与降级

历史迁移必须早于标题 watcher 与 Wiki worker 启动。迁移失败时停止会产生或消费内容事件的功能，
窗口/AFK 及其他不依赖内容事件的能力可以继续运行。

## 5. 消费者

### SPEC-CTP-040：Wiki 与 Agent

Wiki 和 Agent 只消费标题事实与既有派生结果。查询模板不得请求 `text_content` 或 UIA 原文；旧事件
缺少上下文标题时只使用系统窗口标题。

### SPEC-CTP-041：桌面 API

状态与时间线 API 不得返回 UIA 原文。标题字段可以展示，但必须保持事件白名单，不得拼接隐藏控件
文本。

## 6. 验收

- 使用秘密标记构造 UIA 输入，确认采集结果、heartbeat JSON、数据库、日志和迁移文件均不包含标记。
- 验证所有写入入口拒绝正文键，非内容 bucket 的既有通用字段不受影响。
- 验证新采集器不会产生 `ocr_title` 或 `ocr_chars`，历史事件仍可读取。
- 验证历史迁移幂等、失败可重试且不留下明文副本。
