## Context

内容采集当前把 Chrome/Edge/Firefox 等列入 UIA 排除，因此拿不到地址栏。窗口事件只有 app/title。JDK 读不到进程命令行。旧摘要文本不会因隐私策略变化自动改写。

## Goals / Non-Goals

**Goals:**

- 新采集的浏览器活动能按主机名排除，并能识别无标题标记的无痕窗口（在无障碍暴露该信息时）。
- 已保存摘要中的敏感片段被本地替换。
- 留下上层摘要与隐私/置信度的 live 评测证据。

**Non-Goals:**

- 不持久化完整 URL 或 UIA 树。
- 不自动重跑模型重算旧摘要。
- 不保证所有浏览器/所有版本都能暴露无痕或地址栏。

## Decisions

### 1. 浏览器仍禁止上下文标题，但允许 chrome 探测

`ContextCapturePolicy` 继续禁止把浏览器正文或 Document.Name 当作上下文标题。新增 `BrowserChromeProbe`：仅在 chrome/msedge/firefox 等进程上遍历临时 UIA，提取：

- `url_host`：地址栏 Edit/文档 URL 的主机名（小写，无用户信息、无路径）
- `private_browsing`：名称/值含 Incognito、InPrivate、无痕、隐身、Private 等标记时为 true

探测失败时省略字段，行为退化为现状。

### 2. 字段写在内容事件上

扩展内容事件允许列表。Wiki 采样窗口事实时，对重叠的内容事件读取这些字段；内容事实自身也读取。窗口桶不新增字段，避免 events 模块依赖 content UIA。

### 3. 旧摘要本地脱敏

Worker 每轮或启动时扫描当前统计版本的 `SUMMARIZED` 条目：对 `summary`、`primaryTask`、任务片段 title/summary 调用 `redactText`；若有改动则写回并在 metrics.extra 记 `privacyRedactedAt`。不重置状态，不调用模型。

### 4. 评测

新增 `parent-abstract-privacy` 夹具（子摘要含敏感文本与多余主题）。扩展评测入口支持 `children` 输入。live 报告写入 `docs/` 或 change 归档的 evaluation.md，不提交含密钥的 JSON。

## Risks / Trade-offs

- 部分浏览器无障碍不暴露地址栏或无痕标记，仍会漏检；README 保留该限制。
- 内容事件与窗口事件时间不完全对齐时，偶发匹配失败；接受并用重叠区间尽量匹配。
- 旧摘要脱敏可能改变展示文案，但不改变任务结构与统计。
