## ADDED Requirements

### Requirement: SPEC-CTX-020 浏览器 chrome 探测
系统 MAY 对常见浏览器进程做一次临时无障碍探测，仅用于提取 `url_host` 与 `private_browsing`。该探测 MUST NOT 产生上下文标题，MUST NOT 把地址栏完整 URL、控件树或页面正文写入 Snapshot、heartbeat 或事件。探测失败时 SHALL 省略这些字段。

#### Scenario: 地址栏主机名
- **WHEN** 前台为受支持浏览器且无障碍暴露可解析的地址栏 URL `https://user:pass@github.com/org/repo?x=1`
- **THEN** 事件可含 `url_host=github.com`，不含用户信息、路径或查询串

#### Scenario: 无痕 chrome 标记
- **WHEN** 前台为受支持浏览器且无障碍名称或值含 Incognito、InPrivate、无痕或隐身
- **THEN** 事件可含 `private_browsing=true`，即使系统窗口标题没有这些词

#### Scenario: 浏览器仍无上下文标题
- **WHEN** 前台为 Chrome 且无障碍含页面正文
- **THEN** 系统不输出 `context_title`，探测只影响可选的 `url_host` / `private_browsing`
