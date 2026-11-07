## ADDED Requirements

### Requirement: SPEC-WCH-004 采集层排除应用
系统 SHALL 读取 `events.collection.excludeApps` 配置的可执行文件名列表，并按完整文件名、不区分大小写匹配前台应用。前台应用命中该列表时，窗口采集与标题采集 MUST NOT 发送 heartbeat，本地事件库 MUST NOT 产生该应用的窗口事件或内容事件，也 MUST NOT 对该窗口发起无障碍查询；AFK 采集 SHALL 不受影响。切换到排除应用时，上一应用的事件 SHALL 在其最后一次 heartbeat 处结束，MUST NOT 被延长覆盖排除期间；切回非排除应用时 SHALL 开始新事件。该列表为空时采集行为 SHALL 与既有行为一致。列表保存成功后 SHALL 在不重启后端的情况下，于下一个采集轮询周期内按新列表开始或停止采集；已记录的事件 MUST NOT 被删除，排除期间未采集的活动 MUST NOT 被补记。

#### Scenario: 前台为排除应用
- **WHEN** `events.collection.excludeApps` 包含 `PotPlayerMini64.exe`，用户在前台使用 `potplayermini64.exe` 窗口
- **THEN** 窗口桶和内容桶均不新增该应用的事件，且不对该窗口查询无障碍树

#### Scenario: 从普通应用切到排除应用再切回
- **WHEN** 用户先使用 `code.exe`，随后切到排除应用 10 分钟，再切回 `code.exe`
- **THEN** 第一段 `code.exe` 事件不包含这 10 分钟，切回后产生新的 `code.exe` 事件，排除期间不计入任何应用或未识别活动

#### Scenario: 名称只部分匹配
- **WHEN** 排除列表包含 `PotPlayer.exe`，前台应用为 `PotPlayerMini64.exe`
- **THEN** 该应用不命中排除规则，仍按既有行为采集

#### Scenario: 保存后立即生效
- **WHEN** 用户正在前台使用 `PotPlayerMini64.exe`，并在隐私设置中把它设为“不采集”后保存
- **THEN** 不重启后端，下一个采集周期起不再产生该应用的事件；改回“仅对模型隐藏”并保存后，下一个采集周期起恢复采集，期间的活动不被补记
