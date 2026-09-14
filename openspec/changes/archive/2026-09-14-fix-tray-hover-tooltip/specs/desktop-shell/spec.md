## MODIFIED Requirements

### Requirement: SPEC-DSK-TRAY-001 系统托盘
Windows 系统托盘图标的悬停提示 SHALL 显示非空产品名称 `SelfAnalyst`，在主窗口可见或隐藏时均保持有效。系统托盘 SHALL 提供显示窗口、Web版桌面、开机自启动、关于和退出入口。“开机自启动” SHALL 为可勾选项，反映当前分发的系统启动项注册状态，读取失败时显示状态不可用。显示入口及托盘左键点击 SHALL 显示并聚焦主窗口；Web版桌面 SHALL 打开带一次启动 token 的 `/desktop/session`；关于入口 SHALL 显示产品、桌面壳及当前后端地址；退出入口 SHALL 触发应用退出和受管后端关闭。关闭主窗口 SHALL 只隐藏到托盘，不得终止应用。

#### Scenario: 关闭主窗口
- **WHEN** 用户点击窗口关闭按钮
- **THEN** 桌面壳阻止窗口销毁并隐藏主窗口，后台继续运行

#### Scenario: 托盘恢复窗口
- **WHEN** 用户选择“显示窗口”或左键点击托盘图标
- **THEN** 主窗口显示并获得焦点

#### Scenario: 正常退出
- **WHEN** 用户选择托盘“退出”
- **THEN** 应用进入退出流程，并向当前受管后端发送认证 shutdown 请求

#### Scenario: 查看自启动状态
- **WHEN** 用户打开托盘菜单
- **THEN** 自启动项显示当前分发的回读注册状态，不把其他路径注册误认为当前分发已开启

#### Scenario: 悬停识别应用
- **WHEN** 用户将鼠标悬停在 Windows 系统托盘中的应用图标上，主窗口可见或已隐藏
- **THEN** 系统提示显示产品名称 SelfAnalyst，不显示空白提示

