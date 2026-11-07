## MODIFIED Requirements

### Requirement: SPEC-WIKI-PRV-020 摘要输入脱敏与排除
新生成摘要在形成标题事实之前 SHALL 脱敏内网 IP、会议号和账号验证页标题。会议号 SHALL 覆盖“会议号”“会议 ID”“Meeting ID”后跟随的号码，冒号可有可无，号码可用空格或短横分隔。标题包含无痕、隐身、InPrivate、Incognito、隐私浏览或 Private Browsing，或关联内容事件 `private_browsing=true` 时，该活动 MUST NOT 成为标题事实；系统 MUST NOT 声称能识别既无标题标记也无探测字段的无痕窗口。配置的排除应用（`wiki.privacy.excludeApps` 与 `events.collection.excludeApps` 的并集，按可执行文件名，不区分大小写）和排除网站（按标题关键词或 `url_host`，不区分大小写）MUST NOT 成为标题事实，排除应用的名称 MUST NOT 出现在发给模型的应用权重中。

上层周期读取子摘要时，任务标题与描述 SHALL 经过同样的脱敏；被排除的应用 SHALL 从子片段的应用列表中移除，应用全部被排除或标题命中无痕标记、排除网站的子片段 MUST NOT 进入上层输入。活跃时长、AFK 和应用耗时等本地统计 MUST 保持不变。看板当前窗 SHALL 使用同一策略。排除配置保存成功后，之后开始的摘要生成与看板当前窗 SHALL 使用新配置，不需要重启后端；已生成的摘要 MUST NOT 因配置变化被自动重新生成或删除。

#### Scenario: 敏感标题被替换
- **WHEN** 窗口标题包含内网 IP、“会议号：123 456 789”、“Meeting ID 123-456-789”或账号验证页
- **THEN** 模型输入不再包含原 IP、会议号或验证页标题

#### Scenario: 无痕活动不进入摘要
- **WHEN** 窗口标题标明无痕、隐身或 InPrivate，或重叠内容事件带有 `private_browsing=true`
- **THEN** 这些标题不进入模型输入

#### Scenario: 按应用和网站排除
- **WHEN** 用户配置排除了某个应用，或配置了出现在页面标题中的网站关键词，或配置了与 `url_host` 匹配的主机名
- **THEN** 匹配的标题不进入模型输入，排除应用的名称不出现在提示词的应用权重中，该应用的耗时仍保留在本地统计中

#### Scenario: 采集排除前的历史事件
- **WHEN** 某应用只配置在 `events.collection.excludeApps` 中，而事件库仍保留该应用在配置前记录的历史事件
- **THEN** 这些历史事件的标题不进入模型输入，应用名不出现在提示词的应用权重中

#### Scenario: 运行中修改排除配置
- **WHEN** 后端运行期间用户新增排除应用并保存，随后到期生成下一小时摘要
- **THEN** 该小时摘要的模型输入不含该应用的标题与应用名，此前已生成的摘要保持不变

#### Scenario: 旧子摘要中的敏感内容
- **WHEN** 升级前生成的小时摘要的任务标题或描述含有内网 IP、会议号，或只来自被排除的应用
- **THEN** 半天摘要的模型输入不包含原 IP 和会议号，也不包含只来自被排除应用的子片段
