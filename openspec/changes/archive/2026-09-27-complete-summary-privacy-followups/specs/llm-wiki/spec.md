## MODIFIED Requirements

### Requirement: SPEC-WIKI-PRV-020 摘要输入脱敏与排除
新生成摘要在形成标题事实之前 SHALL 脱敏内网 IP、会议号和账号验证页标题。会议号 SHALL 覆盖“会议号”“会议 ID”“Meeting ID”后跟随的号码，冒号可有可无，号码可用空格或短横分隔。标题包含无痕、隐身、InPrivate、Incognito、隐私浏览或 Private Browsing，或关联内容事件 `private_browsing=true` 时，该活动 MUST NOT 成为标题事实；系统 MUST NOT 声称能识别既无标题标记也无探测字段的无痕窗口。配置的排除应用（按可执行文件名，不区分大小写）和排除网站（按标题关键词或 `url_host`，不区分大小写）MUST NOT 成为标题事实，排除应用的名称 MUST NOT 出现在发给模型的应用权重中。

上层周期读取子摘要时，任务标题与描述 SHALL 经过同样的脱敏；被排除的应用 SHALL 从子片段的应用列表中移除，应用全部被排除或标题命中无痕标记、排除网站的子片段 MUST NOT 进入上层输入。活跃时长、AFK 和应用耗时等本地统计 MUST 保持不变。看板当前窗 SHALL 使用同一策略。

#### Scenario: 敏感标题被替换
- **WHEN** 窗口标题包含内网 IP、“会议号：123 456 789”、“Meeting ID 123-456-789”或账号验证页
- **THEN** 模型输入不再包含原 IP、会议号或验证页标题

#### Scenario: 无痕活动不进入摘要
- **WHEN** 窗口标题标明无痕、隐身或 InPrivate，或重叠内容事件带有 `private_browsing=true`
- **THEN** 这些标题不进入模型输入

#### Scenario: 按应用和网站排除
- **WHEN** 用户配置排除了某个应用，或配置了出现在页面标题中的网站关键词，或配置了与 `url_host` 匹配的主机名
- **THEN** 匹配的标题不进入模型输入，排除应用的名称不出现在提示词的应用权重中，该应用的耗时仍保留在本地统计中

#### Scenario: 旧子摘要中的敏感内容
- **WHEN** 升级前生成的小时摘要的任务标题或描述含有内网 IP、会议号，或只来自被排除的应用
- **THEN** 半天摘要的模型输入不包含原 IP 和会议号，也不包含只来自被排除应用的子片段

## ADDED Requirements

### Requirement: SPEC-WIKI-PRV-021 已保存摘要本地脱敏
系统 SHALL 在后台对当前统计版本中状态为 SUMMARIZED 的条目做本地脱敏：对 summary、primaryTask 与任务片段 title/summary 应用与新摘要相同的文本脱敏规则。改写 MUST NOT 调用模型，MUST NOT 改变活跃/AFK/应用耗时，MUST NOT 把条目改回 PENDING。完成改写的条目 SHALL 记录本地脱敏标记，避免重复全量扫描造成无意义写放大。

#### Scenario: 旧小时摘要中的会议号被替换
- **WHEN** 已保存摘要含“参加会议号：361 881 114”
- **THEN** 持久化文本变为含“会议号：[已隐藏]”，条目仍为 SUMMARIZED，metrics 中的活跃秒数不变

### Requirement: SPEC-WIKI-EVAL-030 上层摘要评测夹具
仓库 SHALL 提供基于已完成子摘要的合成评测夹具，覆盖上层主题上限、敏感子文本脱敏，以及可选的真实模型评测入口。普通测试 MUST NOT 默认启用 HTTP。

#### Scenario: 离线夹具可运行
- **WHEN** 运行上层摘要合成夹具的离线评测
- **THEN** 报告生成成功且不发起模型请求
