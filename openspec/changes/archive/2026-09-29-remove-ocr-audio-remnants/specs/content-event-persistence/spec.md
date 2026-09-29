## MODIFIED Requirements

### Requirement: SPEC-CTP-011 内容事件 v2 字段集合与值约束
内容事件 data SHALL 只允许 `schema_version`、`app`、`title`、`context_title`、`context_kind`、
`title_source`、`title_confidence`、`uia_chars`、`url_host` 和 `private_browsing`。系统 SHALL 按以下 Scenario 中的
基础、上下文、诊断和浏览器探测字段契约校验值；任何其他字段 MUST 被拒绝。

#### Scenario: 合法标题事件被接受
- **WHEN** 内容事件包含必需字段和合法可选上下文字段
- **THEN** 系统允许持久化，读取结果只包含 v2 允许字段

#### Scenario: 基础字段契约
- **WHEN** 内容事件提交基础字段
- **THEN** `schema_version` 必须为数值 `2`
- **THEN** `app` 与 `title` 必须为单行字符串，分别最多 260 和 1024 code point
- **THEN** `title_source` 必须为 `window/uia_document/uia_context` 之一

#### Scenario: 上下文字段契约
- **WHEN** 内容事件包含 `context_title`
- **THEN** 该值必须为非空白单行字符串、最多 200 code point，且不是纯 URL、精确匹配的内置通用标签或具有两个及以上句末符的明显句群
- **THEN** `context_kind` 必须为 `chat/article/document/page/unknown` 之一
- **THEN** 可选的 `title_confidence` 必须为 `high/medium/low` 之一

#### Scenario: 诊断计数字段契约
- **WHEN** 内容事件包含 `uia_chars`
- **THEN** 诊断计数必须为数值，且其 64 位整数转换结果非负

#### Scenario: 浏览器探测字段契约
- **WHEN** 内容事件包含 `url_host` 或 `private_browsing`
- **THEN** `url_host` 若存在必须为非空白小写主机名，最多 253 code point，不含用户信息、路径、查询或片段
- **THEN** `private_browsing` 若存在必须为布尔 `true`；为 false 时 MUST 省略该字段

#### Scenario: 正文键或未知键被拒绝
- **WHEN** 内容事件包含正文键或任意不在允许集合中的字段
- **THEN** 系统在写入前拒绝该事件

#### Scenario: 非标题上下文被拒绝
- **WHEN** `context_title` 是纯 URL、忽略大小写后精确匹配内置通用标签、包含换行、至少两个句末符或超过 200 code point
- **THEN** 系统拒绝该内容事件

#### Scenario: 上下文字段依赖不成立
- **WHEN** 事件没有 `context_title` 却包含 `context_kind` 或 `title_confidence`
- **THEN** 系统拒绝该内容事件

#### Scenario: 旧识别字段不再接受
- **WHEN** 内容事件包含 `ocr_chars` 或来源值 `ocr_title`
- **THEN** 系统在写入前拒绝该事件，不保留旧字段兼容待遇

## REMOVED Requirements

### Requirement: SPEC-CTP-012 OCR 历史兼容值与当前采集器
**Reason**: 已移除功能的专用字段和来源兼容退役。
**Migration**: 新写入仅接受现行标题字段；既有迁移扫描到记录时按现行字段白名单净化，舍弃旧计数与无效来源候选，无可用候选时回退系统窗口标题。已完成迁移的历史记录不强制重扫，用户备份不自动删除。
