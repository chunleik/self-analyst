## MODIFIED Requirements

### Requirement: SPEC-DSK-ARCH-001、001a、001b、001c、001d 进程与通信边界
桌面壳 SHALL 为每次启动生成随机 desktop token 和唯一端口文件路径。免安装分发 SHALL 从桌面可执行文件所在目录解析后端 JAR 与 Java runtime；安装模式 SHALL 从 Tauri resource 目录解析带安装布局标记的后端 JAR 与 Java runtime。两类分发默认 SHALL 以当前用户应用数据目录为后端工作目录；只有非安装分发的 EXE 同级存在有效 `portable.marker` 普通文件时 SHALL 以 EXE 目录为工作目录。用户目录 SHALL 沿用稳定应用标识，不含应用版本号；安装布局 SHALL 优先于便携标记。目录或标记检查失败 MUST 报告错误，不静默切换目录。桌面壳 SHALL 通过环境变量传递 token 与端口文件。后端 SHALL 在 HTTP 服务开始接受请求之前注册桌面生命周期路由，MUST NOT 依赖服务启动后的追加注册；随后再原子发布实际监听端口。业务交互 SHALL 使用该端口上的回环 HTTP；WebView MUST 加载 `http://localhost:<actualPort>/desktop-ui/`，不得回退到硬编码端口。除 `/desktop/session` 外的 `/desktop/*` 路由 SHALL 要求有效 header token 或会话 cookie。

#### Scenario: 动态端口启动
- **WHEN** Java 后端使用配置端口启动并发布合法端口文件
- **THEN** 桌面壳使用该实际端口完成健康检查、创建 WebView 和托盘链接

#### Scenario: 生命周期路由注册时机
- **WHEN** 后端带 desktop token 启动并开始接受请求
- **THEN** `/desktop/lifecycle/health` 自首个请求起即可路由，带有效 token 时不得返回 404

#### Scenario: 非默认端口
- **WHEN** 后端实际端口不是 5700
- **THEN** 壳的健康检查、WebView、浏览器入口和退出请求仍使用同一实际端口

#### Scenario: 受保护桌面请求
- **WHEN** 请求访问 `/desktop/*` 且路径不是 `/desktop/session`
- **THEN** 请求必须携带本次启动 token 的 header 或有效会话 cookie

#### Scenario: 安装模式的数据目录
- **WHEN** 从包含安装布局标记的 NSIS 安装目录启动桌面壳
- **THEN** 壳从 Tauri resource 目录加载 JAR/runtime，并把后端工作目录设为当前用户应用数据目录，即便 EXE 旁存在便携标记

#### Scenario: 默认免安装运行
- **WHEN** 从没有便携标记的解压目录启动
- **THEN** JAR/runtime 仍来自 EXE 目录，后端使用与安装版相同的用户运行根

#### Scenario: 显式便携运行
- **WHEN** 非安装分发的 EXE 旁有有效 `portable.marker`
- **THEN** 后端使用 EXE 目录为运行根，并在其 `data/` 执行数据准入

#### Scenario: 目标不可写
- **WHEN** 所选运行根无法创建或写入
- **THEN** 启动失败并说明目录不可用，不回退到另一个数据位置

### Requirement: SPEC-DSK-BLD-001 正式构建产物
正式 Windows 构建 SHALL 生成 `SelfAnalyst.exe` 与 `self-analyst-app.jar`；accessibility sidecar SHALL 作为应用 JAR 资源随包提供，而不是依赖运行机器安装 Rust。便携构建 SHALL 额外包含精简 Java runtime，生成免安装 ZIP 和 SHA-256；标准免安装 ZIP MUST NOT 预置 `portable.marker`、用户配置或可变数据树，用户可显式添加便携标记启用随包数据。NSIS 构建 SHALL 把后端 JAR、精简 Java runtime 与安装布局标记放入 Tauri resource，并生成安装包和 SHA-256；安装、升级或卸载应用文件 MUST NOT 把用户数据库和配置放在安装目录中，也不得预置用户数据以绕过首次准入。正式产物 MUST NOT 恢复已移除的 OCR 或音频工具目录。WebView2 继续由系统提供。

#### Scenario: 普通分发目录
- **WHEN** 执行正式 dist 构建
- **THEN** 输出目录包含桌面可执行文件和后端 JAR，JAR 内含当前平台边车资源

#### Scenario: 便携分发目录
- **WHEN** 执行 portable 构建
- **THEN** 输出额外包含可运行 Java runtime，并打包为免安装 ZIP；ZIP 不预建用户数据、用户配置或便携标记

#### Scenario: NSIS 安装包
- **WHEN** 执行 installer 构建
- **THEN** 安装包包含桌面壳、后端 JAR、Java runtime 与安装布局标记，并可完成静默安装、后端冒烟和卸载验证

## ADDED Requirements

### Requirement: SPEC-DSK-DATA-001 数据位置展示
设置界面 SHALL 只读展示当前运行模式、实际运行根和数据根。受管桌面 SHALL 提供打开当前数据目录入口，只打开当前实例解析的目录，不接受任意路径。非原生浏览器入口 SHALL 显示路径并准确说明原生打开不可用。展示和打开 MUST NOT 修改目录、迁移数据或切换运行模式。

#### Scenario: 查看默认数据位置
- **WHEN** 默认模式用户进入设置
- **THEN** 显示当前用户运行根及其 `data/`，与实际配置和默认业务路径一致

#### Scenario: 打开便携数据目录
- **WHEN** 显式便携模式用户在受管桌面点击打开数据目录
- **THEN** 打开 EXE 同级 `data/`，不打开用户模式的另一个目录

#### Scenario: 原生打开失败
- **WHEN** 系统拒绝打开目录或当前入口没有原生能力
- **THEN** 保留实际路径显示并反馈失败或不可用，不报告成功
