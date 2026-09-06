## MODIFIED Requirements

### Requirement: SPEC-FILE-073 文件设置热更新
`PUT /desktop/files/settings` SHALL 支持启用状态与监控目录列表的热更新，并 SHALL 拒绝非绝对、
无法解析、不存在、不是目录或包含逗号的路径，以及超过 100 个路径条目的列表。空目录列表 SHALL 可与
`enabled=true` 一起提交，表示已启用但尚未配置监控根。该接口不负责更新其他过滤配置。设置响应
SHALL 明确指示变更是否需要重启。

#### Scenario: 更新监控目录
- **WHEN** 用户提交有效目录列表和启用状态
- **THEN** 系统更新当前采集范围并返回运行状态及重启提示

#### Scenario: 普通文件不能作为监控根
- **WHEN** 用户把普通文件路径提交为监控根
- **THEN** 系统拒绝该设置且不启动对应采集

#### Scenario: 无目录也可启用
- **WHEN** 用户提交 `enabled=true` 且监控目录列表为空
- **THEN** 系统保存启用状态并返回等待配置目录的运行状态

### Requirement: SPEC-FILE-071 桌面界面元数据术语
桌面界面 SHALL 使用“已采集”和“元数据”等准确术语，MUST NOT 将文件能力描述为内容索引、摘要或
语义检索。采集禁用时 MUST NOT 把历史元数据渲染为正在采集的结果。

#### Scenario: SPEC-FILE-TST-008 文件页面展示
- **WHEN** 用户打开已启用采集的文件页面
- **THEN** 界面只以元数据采集术语展示当前状态，并且不暗示系统读取正文

## ADDED Requirements

### Requirement: SPEC-FILE-074 文件界面跟随采集开关
桌面端「文件」页签、文件页与右上角文件状态入口 SHALL 仅在文件采集启用时可见。采集关闭时
MUST NOT 展示这些入口。若用户正在文件页时关闭采集，界面 SHALL 立即离开文件页。启用后可从
齿轮配置中的 `file.watch.enabled` 打开采集。

#### Scenario: SPEC-FILE-TST-021 默认关闭时隐藏文件页签
- **WHEN** 文件采集未启用
- **THEN** 顶栏不显示「文件」页签、文件页和右上角文件状态入口

#### Scenario: SPEC-FILE-TST-022 启用后显示文件页签
- **WHEN** 用户启用文件采集
- **THEN** 顶栏显示「文件」页签和文件状态入口，用户可以打开文件页

#### Scenario: SPEC-FILE-TST-023 关闭时隐藏文件状态入口
- **WHEN** 文件采集未启用
- **THEN** 右上角不显示文件状态入口

#### Scenario: SPEC-FILE-TST-024 关闭采集后离开文件页
- **WHEN** 用户正在文件页并将采集关闭
- **THEN** 文件页签立即隐藏，界面离开文件页

### Requirement: SPEC-FILE-075 采集开关与监控目录分面
桌面采集设置 SHALL 只管理启用开关，MUST NOT 在该设置面编辑监控目录。监控目录的添加、修改和移除
SHALL 在已启用的文件页完成。系统 SHALL 允许在尚未配置监控目录时启用采集；此时文件页可见，采集
处于等待配置目录的降级状态。

#### Scenario: SPEC-FILE-TST-025 设置面只开关
- **WHEN** 用户打开文件采集设置
- **THEN** 界面只提供启用开关，不提供监控目录编辑

#### Scenario: SPEC-FILE-TST-026 文件页管理监控目录
- **WHEN** 文件采集已启用且用户打开文件页
- **THEN** 用户可以在该页添加、保存监控目录

#### Scenario: SPEC-FILE-TST-027 无目录也可先启用
- **WHEN** 用户在未配置监控目录时启用文件采集
- **THEN** 系统保存启用状态，文件页可见，且不因缺少目录而拒绝该开关
