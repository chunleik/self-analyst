## ADDED Requirements

### Requirement: SPEC-TOML-PRIV-001 应用排除配置与热生效
系统 SHALL 支持 `events.collection.excludeApps` 字符串键，默认空，表示前台时完全不采集的应用；`wiki.privacy.excludeApps` 与 `wiki.privacy.excludeSites` SHALL 保持原键名，语义为对模型隐藏的应用与网站。三个键均 SHALL 接受逗号或分号分隔的列表，去除首尾空白并忽略空项，按不区分大小写比较；应用 SHALL 按可执行文件名完整匹配。

三个键 SHALL 热生效：通过隐私页签、高级配置 raw 编辑器或结构化配置接口保存成功后，系统 SHALL 立即把新值应用到采集排除和模型可见过滤，保存结果 MUST NOT 把这三个键列为需重启，有效配置中的运行值 SHALL 与保存值一致。保存失败时运行中的排除规则 MUST 保持不变。三个键 MUST NOT 加入 Agent 配置工具的可写范围。配置说明 SHALL 分别描述“不采集”与“对模型隐藏”，并说明不采集的应用同样对模型隐藏。

#### Scenario: raw 编辑器保存采集排除
- **WHEN** 用户在高级配置的 `[events.collection]` 表中设置 `excludeApps = "PotPlayerMini64.exe; PotPlayerMini.exe"` 并保存
- **THEN** 保存成功，结果不报告需重启，有效配置的运行值为该列表，采集按新列表生效

#### Scenario: 保存失败不改变运行规则
- **WHEN** 同一次保存包含其他非法配置导致整体校验失败
- **THEN** 配置文件与运行中的排除规则均保持原值

#### Scenario: Agent 尝试修改排除列表
- **WHEN** Agent 配置工具尝试设置 `events.collection.excludeApps` 或 `wiki.privacy.excludeApps`
- **THEN** 工具拒绝该键，不写入配置、不报告成功

### Requirement: SPEC-CFGUI-PRIV-001 隐私设置页签
配置弹窗 SHALL 提供“隐私”页签，以一张应用清单管理应用隐私。每个应用 SHALL 恰好出现一次，并通过模式下拉在“不采集”与“仅对模型隐藏”之间选择，可单独移除；保存时“不采集”的应用写入 `events.collection.excludeApps`，“仅对模型隐藏”的应用写入 `wiki.privacy.excludeApps`。加载时同一应用同时出现在两个键中 SHALL 显示为“不采集”。

添加应用 SHALL 使用可搜索下拉：候选为近 30 天窗口活动中出现过的应用，按扣除前的累计时长降序，最多 100 个，已在清单中的应用不重复列出；用户 SHALL 也能输入不在候选中的可执行文件名。候选读取失败或为空时 SHALL 仍允许手动输入并提示原因。页签 SHALL 提供排除网站的手动添加与移除。

保存 SHALL 一次提交全部应用与网站，成功后提示已生效且无需重启；失败时 SHALL 保留用户编辑并显示错误。存在未保存修改时切换页签或关闭弹窗 SHALL 沿用既有的放弃确认。页签文案 SHALL 支持中英文，并说明“不采集”期间不留本地记录、已生成摘要与会话中模型已看到的内容不会被追溯修改。

#### Scenario: 从下拉添加并设为不采集
- **WHEN** 用户在隐私页签的下拉中搜索 `potplayer`，选择 `PotPlayerMini64.exe`，模式选“不采集”并保存
- **THEN** `events.collection.excludeApps` 包含该应用，页签提示已生效，该应用不再出现在添加下拉的候选中

#### Scenario: 切换模式
- **WHEN** 清单中 `PotPlayerMini64.exe` 为“不采集”，用户改为“仅对模型隐藏”并保存
- **THEN** 该应用从 `events.collection.excludeApps` 移除并写入 `wiki.privacy.excludeApps`，不需要重启即恢复本地采集

#### Scenario: 手动输入未出现过的应用
- **WHEN** 候选中没有目标应用，用户输入 `vlc.exe` 并添加
- **THEN** 清单新增该应用，默认模式为“仅对模型隐藏”，保存后按所选模式生效

#### Scenario: 未保存时离开
- **WHEN** 用户修改了清单但未保存就切换到其他配置页签
- **THEN** 系统先请求确认放弃修改，取消后保留当前编辑
