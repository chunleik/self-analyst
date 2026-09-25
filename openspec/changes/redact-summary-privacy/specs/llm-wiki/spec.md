## ADDED Requirements

### Requirement: SPEC-WIKI-PRV-020 摘要输入脱敏与排除
新生成摘要在形成标题事实之前 SHALL 脱敏内网 IP、会议号和账号验证页标题。标题包含无痕、InPrivate、Incognito 或隐私浏览，或事件标记为私人浏览时，该活动 MUST NOT 成为标题事实。配置的排除应用和排除网站 MUST NOT 成为标题事实。活跃时长、AFK 和应用耗时 MUST 保持不变。看板当前窗 SHALL 使用同一策略。

#### Scenario: 敏感标题被替换
- **WHEN** 窗口标题包含内网 IP、会议号或账号验证页
- **THEN** 模型输入不再包含原 IP、会议号或验证页标题

#### Scenario: 无痕活动不进入摘要
- **WHEN** 窗口标题标明无痕或 InPrivate，或事件带有私人浏览标记
- **THEN** 这些标题不进入模型输入

#### Scenario: 按应用和网站排除
- **WHEN** 用户配置排除了某个应用或网站
- **THEN** 匹配的标题不进入模型输入，该应用的耗时仍保留在本地统计中
