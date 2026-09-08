# 桌面壳规格

## Purpose

定义 Windows Tauri 桌面壳的单实例、Java 后端生命周期、动态端口握手、桌面认证、WebView、系统托盘和正式分发行为，确保桌面入口只连接本次受管的回环后端。

## Requirements

### Requirement: SPEC-DSK-ARCH-001、001a、001b、001c、001d 进程与通信边界
桌面壳 SHALL 为每次启动生成随机 desktop token 和唯一端口文件路径。便携模式 SHALL 从桌面可执行
文件所在目录解析后端 JAR 与 Java runtime，并以该目录作为后端工作目录；安装模式 SHALL 从 Tauri
resource 目录解析带安装布局标记的后端 JAR 与 Java runtime，并以当前用户的应用数据目录作为后端
工作目录。桌面壳 SHALL 通过环境变量传递 token 与端口文件。后端 SHALL 在 HTTP 服务开始接受请求之前
注册桌面生命周期路由，MUST NOT 依赖服务启动后的追加注册；随后再原子发布实际监听端口。业务交互
SHALL 使用该端口上的回环 HTTP；WebView MUST 加载
`http://localhost:<actualPort>/desktop-ui/`，不得回退到硬编码端口。除 `/desktop/session` 外的
`/desktop/*` 路由 SHALL 要求有效 header token 或会话 cookie。

#### Scenario: 动态端口启动
- **WHEN** Java 后端使用配置端口启动并发布合法端口文件
- **THEN** 桌面壳使用该实际端口完成健康检查、创建 WebView 和托盘链接

#### Scenario: 生命周期路由注册时机
- **WHEN** 后端带 desktop token 启动并开始接受请求
- **THEN** `/desktop/lifecycle/health` 自首个请求起即可路由，带有效 token 时不得返回 404

#### Scenario: 非默认端口
- **WHEN** 后端实际端口不是 5700
- **THEN** 桌面壳的健康检查、WebView、浏览器入口和退出请求仍使用同一实际端口

#### Scenario: 受保护桌面请求
- **WHEN** 请求访问 `/desktop/*` 且路径不是 `/desktop/session`
- **THEN** 请求必须携带本次启动 token 的 header 或有效会话 cookie

#### Scenario: 安装模式的数据目录
- **WHEN** 从包含安装布局标记的 NSIS 安装目录启动桌面壳
- **THEN** 壳从 Tauri resource 目录加载 JAR/runtime，并把后端工作目录设为当前用户应用数据目录

### Requirement: SPEC-DSK-TRAY-001 系统托盘
系统托盘 SHALL 提供显示窗口、Web版桌面、开机自启动、关于和退出入口。“开机自启动” SHALL 为可勾选项，反映当前分发的系统启动项注册状态，读取失败时显示状态不可用。显示入口及托盘左键点击 SHALL 显示并聚焦主窗口；Web版桌面 SHALL 打开带一次启动 token 的 `/desktop/session`；关于入口 SHALL 显示产品、桌面壳及当前后端地址；退出入口 SHALL 触发应用退出和受管后端关闭。关闭主窗口 SHALL 只隐藏到托盘，不得终止应用。

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

### Requirement: SPEC-DSK-WIN-001 窗口与单实例
主窗口 SHALL 默认使用 1200×800、最小 800×600，并在创建后居中。Windows 桌面壳 SHALL 使用命名 mutex 保证单实例；手动首次启动 SHALL 显示窗口，自动首次启动 SHALL 创建隐藏窗口。重复启动 MUST 在启动 Java 后端前停止第二实例：自动重复启动 SHALL 静默退出且不改变已有窗口状态；手动重复启动 SHALL 请求已有实例恢复、显示并聚焦主窗口，不再仅提示已运行。已有实例正在初始化时 SHALL 保留唤起请求并在窗口就绪后执行；通信失败时 SHALL 向手动启动用户反馈失败且不得启动第二个后端。桌面包 SHALL 使用系统 Evergreen WebView2 Runtime，不在便携包中捆绑固定 WebView2 版本。

#### Scenario: 首次启动窗口
- **WHEN** 当前没有其他桌面实例且用户手动启动
- **THEN** 壳取得单实例 mutex，并按约定尺寸创建居中可见主窗口

#### Scenario: 重复启动
- **WHEN** 命名 mutex 已由现有实例持有且用户再次手动启动
- **THEN** 新进程请求已有窗口恢复并退出，不启动第二个 Java 后端

#### Scenario: 自动重复启动
- **WHEN** 已存在实例且系统再次执行自动启动入口
- **THEN** 第二实例静默退出，不弹提示、不显示或隐藏已有窗口

#### Scenario: 初始化期间手动打开
- **WHEN** 自动启动的首个实例尚未创建窗口，用户又手动启动
- **THEN** 已有实例在窗口就绪后显示窗口，整个过程只有一个受管后端

#### Scenario: 唤起通信失败
- **WHEN** 手动第二实例无法在有限时间内通知已有实例
- **THEN** 第二实例报告唤起失败并退出，不绕过单实例限制

### Requirement: SPEC-DSK-BACKEND-001、001a、001b、001c、001d、001e Java 后端管理
桌面壳 SHALL 优先使用已解析分发根目录中的 Java runtime 执行 `self-analyst-app.jar`，仅在分发未携带
runtime 时回退到系统 Java，并注入 desktop token 和唯一端口文件。带安装布局标记但缺少后端 JAR 的
分发 MUST fail closed，不得回退到可执行文件目录中的其他 JAR。壳 SHALL 最多等待 30 秒取得
`1..65535` 的十进制端口，再最多等待 30 秒以 token 调用
`/desktop/lifecycle/health`。端口文件读取后 SHALL 清理。后端提前退出、端口非法、端口等待超时或
健康检查超时时，桌面启动 MUST fail closed，不使用猜测端口。正常退出 SHALL 请求认证 shutdown，
等待有限时间后终止仍存活的子进程；Windows Job Object SHALL 在壳异常终止时回收受管后端。

#### Scenario: 合法端口与健康检查
- **WHEN** 后端在期限内发布合法端口且认证健康检查成功
- **THEN** 壳记录端口、删除握手文件并创建桌面入口

#### Scenario: 非法或缺失端口
- **WHEN** 端口文件内容为 0、超过 65535、非数字，或在期限内未出现
- **THEN** 壳退出启动流程，不创建使用硬编码端口的窗口

#### Scenario: 后端启动期间退出
- **WHEN** Java 子进程在发布端口或通过健康检查前退出
- **THEN** 壳立即结束启动，并保留后端日志供诊断

#### Scenario: 后端未响应 shutdown
- **WHEN** 正常退出请求后 Java 子进程在等待期内仍未结束
- **THEN** 壳终止并等待该受管子进程退出

#### Scenario: 安装资源不完整
- **WHEN** 安装布局标记存在但安装 resource 目录缺少后端 JAR
- **THEN** 壳在启动任何后端前失败，不使用相邻目录中的未知 JAR

### Requirement: SPEC-DSK-WEB-001 WebView 与浏览器认证
WebView SHALL 从实际后端端口加载桌面 UI。初始化脚本 SHALL 只为同源且路径以 `/desktop/` 开头的 fetch 注入 `X-SelfAnalyst-Token`，MUST NOT 向外部 origin 发送 token。系统浏览器入口 SHALL 通过 `/desktop/session?token=...` 交换设置为 `HttpOnly; SameSite=Strict; Path=/desktop` 的会话 cookie，并重定向到桌面 UI；无效 token SHALL 返回 403。服务 SHALL 校验 Host 和 Origin 的回环边界，业务接口 MUST NOT 接受 query 参数 token。

#### Scenario: 同源桌面请求
- **WHEN** WebView fetch 的目标与当前页面同源且路径位于 `/desktop/`
- **THEN** 初始化脚本注入本次启动 token header

#### Scenario: 外部请求
- **WHEN** WebView fetch 的目标不是当前页面 origin
- **THEN** 初始化脚本不注入 desktop token

#### Scenario: 浏览器会话交换
- **WHEN** 浏览器以有效 token 请求 `/desktop/session`
- **THEN** 后端设置仅作用于 `/desktop` 的 HttpOnly、SameSite=Strict cookie 并重定向到 `/desktop-ui/`

#### Scenario: 无效浏览器 token
- **WHEN** `/desktop/session` 收到错误 token
- **THEN** 后端返回 403，且不建立会话 cookie

#### Scenario: 业务接口拒绝 query token
- **WHEN** 请求只在普通 `/desktop/*` 业务 URL 的 query 中提供 token
- **THEN** 后端不把该参数视为有效认证凭据

### Requirement: SPEC-DSK-RAW-001 原始事件接口强制受管桌面会话
原始事件桌面接口 MUST 使用本次 Tauri 启动生成的 header token 或由 `/desktop/session` 交换的有效 cookie。后端没有配置本次启动 token 时，该接口 MUST fail-closed，且不得沿用一般桌面接口的无 token 开发降级行为。

#### Scenario: WebView 查询原始事件
- **WHEN** 受管 WebView 同源请求原始事件接口
- **THEN** 初始化脚本注入 header token，后端验证成功后才执行查询

#### Scenario: 直接启动 Java 后端
- **WHEN** Java 后端未配置 desktop token 且收到原始事件接口请求
- **THEN** 接口返回能力不可用，不执行查询也不返回 bucket 存在性

### Requirement: SPEC-DSK-BLD-001 正式构建产物
正式 Windows 构建 SHALL 生成 `SelfAnalyst.exe` 与 `self-analyst-app.jar`；accessibility sidecar SHALL
作为应用 JAR 资源随包提供，而不是依赖运行机器安装 Rust。便携构建 SHALL 额外包含精简 Java runtime，
生成免安装 ZIP 和 SHA-256。NSIS 构建 SHALL 把后端 JAR、精简 Java runtime 与安装布局标记放入 Tauri
resource，并生成安装包和 SHA-256；安装、升级或卸载应用文件 MUST NOT 把用户数据库和配置放在安装
目录中。正式产物 MUST NOT 恢复已移除的 OCR 或音频工具目录。WebView2 继续由系统提供。

#### Scenario: 普通分发目录
- **WHEN** 执行正式 dist 构建
- **THEN** 输出目录包含桌面可执行文件和后端 JAR，JAR 内含当前平台边车资源

#### Scenario: 便携分发目录
- **WHEN** 执行 portable 构建
- **THEN** 输出额外包含可运行 Java runtime，并可打包为免安装 ZIP

#### Scenario: NSIS 安装包
- **WHEN** 执行 installer 构建
- **THEN** 安装包包含桌面壳、后端 JAR、Java runtime 与安装布局标记，并可完成静默安装、后端冒烟和卸载验证

### Requirement: SPEC-DSK-BLD-002 开发模式
桌面开发模式 SHALL 使用 Node/pnpm 准备前端依赖并由 Tauri 启动桌面壳；Java 后端 JAR 和必要边车
资源仍须在壳可解析的位置存在。开发模式不改变动态端口、认证和后端生命周期契约。

#### Scenario: 启动桌面开发模式
- **WHEN** 开发者在桌面模块安装依赖并运行 Tauri dev
- **THEN** 壳按与正式模式相同的 token、端口握手和健康检查语义连接后端

### Requirement: SPEC-DSK-AUTO-001 当前用户自启动设置
桌面壳 SHALL 提供当前 Windows 用户登录后的自启动开关，首次使用默认关闭，安装和普通启动 MUST NOT 自动开启。启用 SHALL 注册当前桌面可执行文件的静默启动入口；关闭 SHALL 移除所属启动项而不结束本次运行。设置 SHALL 以系统注册结果为准，不依赖 Java 配置文件；修改后 SHALL 回读确认，失败或无法确认时 MUST 明确反馈且不得报告成功。系统策略可以阻止已注册入口执行，注册成功 MUST NOT 被表述为保证登录时执行。

#### Scenario: 初次安装
- **WHEN** 用户首次安装或运行且没有配置自启动
- **THEN** 自启动默认关闭，系统不新增启动项

#### Scenario: 开启与关闭
- **WHEN** 用户通过托盘开启或关闭自启动且操作成功
- **THEN** 系统注册或移除所属入口，菜单显示回读状态，当前进程继续运行

#### Scenario: 设置失败
- **WHEN** 系统拒绝修改或无法回读注册结果
- **THEN** 显示可理解的错误，不显示设置成功，无法读取时显示状态不可用

### Requirement: SPEC-DSK-AUTO-002 静默启动
自动启动 SHALL 只驻留托盘，主窗口从创建时即隐藏，不闪现、不抢焦点。后端 SHALL 按既有配置、端口握手、认证和生命周期契约运行；自启动 MUST NOT 扩大采集范围或启用被关闭的采集功能。托盘恢复 SHALL 显示主窗口。启动失败 SHALL 按既有失败关闭规则退出并保留诊断日志，不弹出主窗口。

#### Scenario: 登录自动启动
- **WHEN** 已注册入口在用户登录后被系统执行且后端就绪
- **THEN** 托盘可用，主窗口不可见，后端按现有配置运行

#### Scenario: 静默启动后恢复
- **WHEN** 用户通过托盘选择显示窗口或左键点击图标
- **THEN** 主窗口显示并获得焦点

#### Scenario: 后端启动失败
- **WHEN** 自动启动时后端提前退出、端口非法或等待超时
- **THEN** 桌面壳清理受管进程并退出，保留诊断日志，不显示主窗口

### Requirement: SPEC-DSK-AUTO-003 分发与启动项归属
安装版和便携版 SHALL 注册自身可执行文件的绝对路径，并支持路径中的空格及中文。安装版原位置升级 SHALL 保留既有开关选择；卸载 SHALL 清理指向本次安装的所属启动项，不删除其他分发的启动项或用户数据。便携版移动目录后 SHALL 允许用户在新位置重新开启以更新入口，MUST NOT 在普通启动时擅自注册新路径。无法注册的路径 SHALL 明确报错，不写入截断入口。

#### Scenario: 带空格和中文路径
- **WHEN** 用户从包含空格或中文的有效分发目录启用自启动
- **THEN** 后续入口定位该桌面程序，并使用既有分发规则找到后端及数据目录

#### Scenario: 安装版升级和卸载
- **WHEN** 安装版在原位置升级或执行卸载
- **THEN** 升级保留既有开关选择，卸载清理指向该安装的启动项并保留用户数据

#### Scenario: 便携目录移动
- **WHEN** 用户移动便携目录并在新位置重新开启自启动
- **THEN** 注册入口更新到新位置；仅从新位置普通启动不会自行修改入口

#### Scenario: 不同分发共存
- **WHEN** 被卸载安装版与当前注册入口指向的便携版路径不同
- **THEN** 卸载不删除便携版启动项
