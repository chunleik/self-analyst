## MODIFIED Requirements

### Requirement: SPEC-HELP-MENU-001 菜单操作与语言
帮助菜单 SHALL 按顺序提供使用指南、帮助文档、个人知识指南、反馈问题、检查更新、项目官网、关于 SelfAnalyst，文字及可访问名称 SHALL 使用应用当前有效语言的中文或英文资源。文档项、反馈更新项和官网关于项 SHALL 分组显示。菜单 SHALL 覆盖内容而不改变页面布局；点击入口切换展开状态，点击外部、窗口失焦或按 Esc SHALL 关闭。菜单 SHALL 支持 Tab 聚焦入口、Enter 或 Space 展开、上下方向键选择及 Enter 执行，并提供清晰焦点和展开状态。Esc 关闭 SHALL 将焦点返回入口；Tab 离开菜单 SHALL 关闭菜单并继续正常焦点顺序。

#### Scenario: 鼠标开关
- **WHEN** 用户点击帮助、再次点击帮助或点击菜单外部
- **THEN** 菜单分别展开或关闭，时间轴与导航位置不发生移动

#### Scenario: 键盘操作
- **WHEN** 用户用键盘展开菜单、选择项目后按 Esc
- **THEN** 菜单正确显示焦点并关闭，焦点回到帮助入口，操作不触发窗口拖动

#### Scenario: 英文与失焦
- **WHEN** 当前有效语言为英文且用户展开帮助后切换到其他窗口
- **THEN** 菜单显示英文项目并在窗口失焦后关闭；语言选择遵循既有重启生效规则

## ADDED Requirements

### Requirement: SPEC-HELP-WEBSITE-001 项目官网入口
帮助菜单 SHALL 提供中文“项目官网”及英文“Project website”，固定指向 `https://github.com/chunleik/self-analyst`。桌面版 SHALL 使用系统默认浏览器打开；Web 版 SHALL 在独立标签页打开并抑制 Referer。目标 MUST NOT 携带查询参数、desktop token、会话 cookie、后端地址或用户活动内容，MUST NOT 允许前端传入任意目标 URL。操作 SHALL 保留应用当前页面、会话及未发送草稿，菜单关闭后焦点返回帮助入口。应用可检测到打开失败时 SHALL 显示当前界面语言的错误及可复制目标链接，不报告成功；桌面失败提示中的再次打开操作 SHALL 继续通过系统默认浏览器，不得导航嵌入式应用页面。

#### Scenario: 中文或英文桌面入口
- **WHEN** 用户在桌面帮助菜单选择“项目官网”或“Project website”
- **THEN** 系统默认浏览器打开固定仓库首页，应用页面和未发送草稿保持不变，不发送运行实例或活动信息

#### Scenario: Web 入口
- **WHEN** 用户在 Web 帮助菜单选择项目官网
- **THEN** 仓库首页在独立标签页打开，无原生调用，当前应用不跳转，不附带 Referer

#### Scenario: 可检测失败与恢复
- **WHEN** 系统拒绝打开浏览器或 Web 端打开动作抛出异常
- **THEN** 显示本地化失败提示及固定目标链接，用户可以复制链接、关闭提示或再次尝试，应用和草稿保持可用；桌面重试继续使用受限原生外链路径

### Requirement: SPEC-HELP-DOCS-001 帮助文档与个人知识指南
帮助菜单 SHALL 在“使用指南”后提供中文“帮助文档”“个人知识指南”及对应英文“Documentation”“Personal knowledge guide”。帮助文档 SHALL 指向固定项目文档索引 `https://github.com/chunleik/self-analyst/blob/main/docs/README.md`，个人知识指南 SHALL 指向 `https://github.com/chunleik/self-analyst/blob/main/docs/personal-ontology.md`，两种界面语言均使用这些已存在的文档，不推导不存在的译本。打开方式、运行数据隔离、失败提示和桌面安全重试 SHALL 与项目官网保持一致。现有使用指南 SHALL 继续根据语言打开对应 README。

#### Scenario: 查阅知识相关说明
- **WHEN** 用户选择“个人知识指南”或“Personal knowledge guide”
- **THEN** 系统默认浏览器或 Web 新标签页打开现有个人知识文档，可继续访问其中的 Neo4j 同步说明；应用当前页面与草稿不受影响

#### Scenario: 查阅文档索引
- **WHEN** 用户选择“帮助文档”或“Documentation”
- **THEN** 系统默认浏览器或 Web 新标签页打开项目文档索引，链接不包含本地运行数据或凭据

#### Scenario: 文档打开失败
- **WHEN** 打开任一新增文档目标发生可检测错误
- **THEN** 应用显示本地化错误及对应可复制链接，桌面重试仍由系统默认浏览器打开，关闭错误提示后可继续操作菜单
