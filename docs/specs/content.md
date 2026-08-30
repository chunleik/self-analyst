# 上下文标题采集规格

> 状态：现行
>
> 规格前缀：`SPEC-CTX-*`、`SPEC-UIA-*`、`SPEC-WCH-*`
>
> 适用模块：`self-analyst-content`

## 1. 职责边界

### SPEC-CTX-001：采集目标

内容模块只采集“当前活动对象的标题”，不采集对象正文。系统窗口标题不足以表达真实活动时，
模块可以临时查询无障碍树，以识别微信对话人、正在阅读的文章标题、文档标题或页面标题。

### SPEC-CTX-002：读取与保存分离

- 标题识别调用可以在内存中读取完整 UIA 树及其文本。
- 原始 UIA 节点、拼接文本、控件值和正文不得进入事件、日志、缓存或数据库。
- 识别完成后只允许返回一个标题投影：`contextTitle`、`contextKind`、`titleSource`、
  `titleConfidence` 和不可还原正文的 `uiaChars` 计数。
- 无可靠标题时必须返回空上下文标题，不得用正文首句或固定长度截断作为回退。

### SPEC-CTX-003：暂不支持的能力

当前版本不包含屏幕截图、OCR、OCR 样本保存、麦克风或系统声音采集、语音转写以及相关 API、
配置和发布依赖。历史设计和恢复方式见
[removed-ocr-audio.md](removed-ocr-audio.md)。

## 2. 标题识别

### SPEC-CTX-010：候选优先级

候选按下列顺序选择：

1. 应用专用上下文标题，例如微信对话人；
2. 已验证应用结构中的 UIA `Document.Name`，当前仅用于微信文章；
3. 系统窗口标题。

只有前两类作为 `context_title`；系统窗口标题始终保存在 `title` 字段，并使用
`title_source=window`。

### SPEC-CTX-011：候选过滤

以下值不得成为上下文标题：空值、纯空白、通用应用名、通用控件名、纯 URL、包含多行正文、
明显句群、超过 200 个 Unicode code point 的文本。规范化只允许去除首尾空白、压缩内部空白和
移除已知标题后缀，不得进行正文摘要。

### SPEC-CTX-012：微信标题

- 微信聊天场景优先识别当前对话人，输出 `context_kind=chat`。
- 微信文章/阅读场景优先使用 UIA `Document.Name`，输出 `context_kind=article`。
- 微信主窗口标题长期不变时，不能仅保存“微信”作为上下文标题。
- 无法唯一判断时省略 `context_title`。

## 3. UIA 查询

### SPEC-UIA-001：查询输入输出

`PlatformCapture` 提供前台窗口元数据和 UIA 查询能力。Windows 实现通过 Rust
accessibility sidecar 查询活动窗口；Java 侧只消费当前请求的返回值，不长期保存树。
通用 `Document.Name` 不具备标题语义保证，不得直接持久化；只有经过应用专用规则验证、具备富
文档子树且不处于聊天界面的节点才可产生标题候选。

### SPEC-UIA-002：失败降级

边车缺失、超时、崩溃、返回错误或解析失败时，UIA 查询返回空。采集循环继续使用系统窗口标题，
不得截图或调用其他正文识别手段作为回退。

### SPEC-UIA-003：排除规则

密码管理器、认证窗口及项目定义的敏感应用必须跳过 UIA 查询。跳过时仍可记录操作系统提供的
窗口元数据，但不得探查控件内容。

## 4. 采集循环与事件

### SPEC-WCH-001：刷新

活动窗口变化时立即查询标题。窗口稳定时可以按固定间隔重新查询，以捕获单页应用内的对话或
文章切换；刷新间隔属于内部实现，不开放 OCR 相关配置。

### SPEC-WCH-002：内容事件

heartbeat 必须逐字段构造，并符合
[content-title-persistence.md](content-title-persistence.md) 的 v2 白名单。禁止字段包括
`text_content`、`uia_text`、`ocr_text`、`raw_tree`、`content`、`body` 和截图引用。

### SPEC-WCH-003：运行状态

UIA 查询失败属于可降级识别，不中断采集循环。ActivityWatch heartbeat 发送失败时状态变为
`degraded`，后续成功后恢复；错误日志不得包含 UIA 返回正文。

## 5. 验收

- 单元测试覆盖微信对话、文章、通用 UIA Document、无可靠候选和敏感应用排除。
- 测试在临时 UIA 文本中注入秘密标记，确认 `TitleCaptureResult`、事件 JSON 和日志均不包含它。
- 生产依赖不得包含 Tess4J、PaddleOCR 或 Whisper。
- 发布脚本不得下载或打包 OCR/语音工具。
