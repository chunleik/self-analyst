## Why

摘要会把内网地址、会议号、账号验证页和无痕浏览标题原样送给模型。用户也没有办法按应用或网站排除。

## What Changes

- 送入模型前脱敏内网 IP、会议号和账号验证页标题。
- 标题含无痕、InPrivate、Incognito、隐私浏览，或窗口事件标记 `private_browsing` 的活动不进入标题事实。Windows 前台进程命令行包含无痕参数时写入该标记。
- 配置 `wiki.privacy.excludeApps` 与 `wiki.privacy.excludeSites` 排除指定应用和网站。统计指标不变。

## Capabilities

### New Capabilities

（无）

### Modified Capabilities

- `llm-wiki`：新增摘要隐私过滤。

## Impact

- 标题采样、窗口采集、桌面当前窗和 Wiki 事实构建共用同一策略。
