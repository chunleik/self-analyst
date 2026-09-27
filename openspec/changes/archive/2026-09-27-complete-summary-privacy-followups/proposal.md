## Why

摘要隐私的剩余缺口仍在：无标题标记的无痕窗口进不了排除；网站排除只能猜标题；已保存的旧摘要里仍可能有内网地址和会议号；上层摘要抽象还没有真实模型评测证据。

## What Changes

- 对常见浏览器做有限 UIA 探测：只持久化 `url_host` 与 `private_browsing`，不存完整 URL、不存控件树、不把地址栏当作上下文标题。
- `wiki.privacy.excludeSites` 同时按标题关键词和 `url_host` 匹配；`private_browsing=true` 的活动不进入标题事实。
- 启动后对已保存的 `SUMMARIZED` 条目做一次本地脱敏与标记清理，不调用模型、不改写统计。
- 评测夹具增加上层子摘要案例，并用已安装 LLM 配置留下 live 评测证据。

## Capabilities

### New Capabilities

（无）

### Modified Capabilities

- `title-capture`：允许对浏览器做仅限 chrome 的探测投影。
- `content-event-persistence`：内容事件允许 `url_host` 与 `private_browsing`。
- `llm-wiki`：网站排除与无痕标记使用上述字段；旧摘要本地脱敏；上层摘要评测夹具。

## Impact

- `self-analyst-content` 浏览器探测与 heartbeat 字段。
- 内容事件校验允许字段。
- `WikiPrivacyPolicy` / `WikiTitleSampler` / `WikiFactBuilder`。
- `WikiStore` 或 worker 一轮本地脱敏。
- `WikiQualityEvaluation` 与 `wiki-quality` 夹具；README 同步说明。
