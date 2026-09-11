# SelfAnalyst 架构

## 1. 总览

SelfAnalyst 使用 Java 21 Maven 多模块后端、Tauri 桌面壳和 Rust accessibility sidecar。系统以
“事实采集—本地存储—派生分析—Agent 查询”为主链路；内容事实限定为标题，不保存屏幕正文。

```text
Win32 前台窗口 ───────────────┐
Rust UIAutomation（临时树） ──┼─> TitleCapture ─> 内容事件 v2 ─┐
窗口/AFK watcher ────────────────────────────────> window/AFK ─┼─> 事件服务 ─> Wiki 聚合

用户配置的监控目录 ─> 文件系统元数据采集 ─> file-watch.db ─> FileTools / 桌面 API
                                   └─> metadata-only heartbeat ─> 事件服务通用历史

通过策略的 heartbeat/events/import ─> 月度 raw SQLite（永久、只追加）
                                      └─> 幂等投影器 ─> events.db（合并、可重建）
```

OCR、屏幕截图和声音/语音链路当前不存在。恢复背景见
[archive/removed-features/removed-ocr-audio.md](archive/removed-features/removed-ocr-audio.md)。

## 国际化资源与语言生效

`self-analyst-app/src/main/resources/i18n/languages.json` 注册正式语言及日期 Locale。后端启动时解析有效语言并固定在 Config 中，经桌面状态通道返回语言元数据；普通模型配置热更新不应用待重启语言。原生壳嵌入同一注册表和原生消息资源，使用本次受管端口及 token 读取后端语言后建立正常桌面入口。

页面消息位于 `desktop-ui/locales/<语言>.json`，后端固定消息位于 `i18n/messages/<语言>.json`，原生消息位于 `i18n/native/<语言>.json`；Agent Markdown 提示词继续位于 `prompts/agent/`。查找依次使用当前语言、英文、key，参数只替换一次。桌面错误可携带 `errorCode` 和 `errorParams`，保留既有错误字段及 HTTP 状态；未知错误保留可公开诊断信息，由本地化通用文案包裹。

扩展语言时增加注册项及上述各域资源，提供对应 Markdown 提示词，并运行 Java、Node 和 Rust 测试。各语言的目录 key、参数集合和提示词占位符需通过完整性校验；测试用第三语言仅放在测试资源中，不加入正式清单。无需修改业务渲染点或引入前端打包器。日期 Locale 仅影响展示，不改变时区、存储时间戳或聚合时间窗。

## 2. 模块边界

| 模块 | 边界 |
|------|------|
| `self-analyst-events` | 嵌入式事件服务、SQLite 事件存储、内容事件策略和迁移 |
| `self-analyst-content` | 前台窗口查询、UIA 临时读取、标题候选提取和 heartbeat |
| `self-analyst-file` | 文件监控及文件名、路径、大小、创建/修改时间等元数据；除解析 `.gitignore` 过滤规则外禁止读取正文 |
| `self-analyst-wiki` | 按小时/天/月/年聚合标题事实和派生摘要 |
| `self-analyst-app` | 生命周期、配置、Agent、桌面 REST/SSE 与静态 UI |
| `self-analyst-axsidecar` | OS 无障碍树查询协议；失败返回空，不负责持久化 |
| `self-analyst-desktop` | Tauri 窗口、Java 后端启动与关闭、端口握手 |

## 3. 标题采集链路

`WindowsCapture` 读取前台应用、系统窗口标题和句柄。`TitleCapture` 在窗口变化或稳定刷新时查询
UIA，将整棵树作为单次调用内的临时输入，依次尝试应用专用标题和 `Document.Name`。它返回不含
树或正文的 `TitleCaptureResult`，由 `ContentWatcher` 构造固定字段 heartbeat。

边车不可用、超时或解析失败时，系统保留窗口标题采集，不启动任何截图回退。敏感应用由
`ContextCapturePolicy` 在查询前排除。

## 4. 持久化边界

嵌入式模式以 `{events.raw.dir}/<yyyy>/raw-events-<yyyy-MM>.db` 作为事实源，按服务端 `receivedAt`
的 UTC 月份分区。catalog 和封存 manifest 记录计数、边界、schema 版本与 SHA-256；封存分区只读。
写入顺序固定为隐私校验、raw 事务提交、幂等投影与 checkpoint。投影失败只产生 pending 状态，
不得回滚或删除已经提交的 raw 事实。`events.db`、Wiki 与语义索引都是可删除、可重建的派生数据。

启动时先恢复中断封存，再按 `events.raw.integrity.startupScope` 检查最新或全部分区；分区与 manifest
只读校验，成功更新 catalog 最近校验时间，失败则记录隔离并终止启动，不发布端口或启动采集。

内容事件 v2 允许 `app`、`title`、可选 `context_title/context_kind`、`title_source`、可选
`title_confidence`、`uia_chars` 和时间元数据。共享写入策略覆盖 HTTP heartbeat/events、导入和
内部存储调用。历史 v1 内容会在 watcher 启动前净化。

Wiki 只能消费标题事实；文件采集器不得读取普通文件正文、计算内容哈希或调用内容摘要/embedding；
只允许通过不跟随链接且有大小/身份校验的入口，在内存中读取监控树内 `.gitignore` 以决定路径是否排除；
规则及其编译后的 matcher 只可保留在进程内缓存，不得进入持久化、日志、错误记录或外发数据。
任何日志、异常、失败记录或备份都不得绕开相应规格保存原始输入。

## 5. 生命周期

`AppSession` 的主要顺序是：加载配置 → 初始化/验证 raw → 初始化并恢复事件投影 →
启动 HTTP → 启动窗口/AFK 与标题 watcher → 启动 Wiki/文件/Agent → 启动桌面服务。关闭时先停止
产生新事件的 watcher，再等待 raw/投影事务完成并关闭数据库。raw 初始化失败不得启动采集器；
投影恢复失败时只允许 raw 接收与诊断，Wiki 等依赖完整投影的消费者保持降级。

Windows 登录自启动由 Tauri 壳管理当前用户 Run 入口 `SelfAnalystDesktop`，注册桌面 EXE 的
`--autostart` 启动方式。首次使用默认关闭；自动启动复用后端生命周期并从创建时隐藏主窗口。
单实例 mutex 与固定窗口唤起事件按当前用户、登录会话隔离，手动第二实例请求恢复已有窗口，自动第二实例
静默退出；窗口尚未就绪的唤起请求会保留到创建完成。系统注册状态不进入 Java 用户配置。

不存在声音 watcher、声音控制器或 OCR 引擎生命周期。

## 6. 配置兼容

`SupportedKeys` 是现行配置白名单。`DeprecatedKeys` 保存已移除 OCR/声音键的墓碑：旧文件加载时
静默忽略，桌面配置响应不暴露，结构化保存不写回。此兼容层不创建旧模块依赖。

配置解析区分用户覆盖、环境变量兜底和当前运行快照。ConfigApplicationService 统一协调应用内的
raw、结构化及 Agent 工具保存；候选模型准备和写盘成功后发布新版本，失败则保留旧文件与运行实例。
文件设置专用接口参加同一持久化协调，并继续报告实际已应用的采集配置。

SelfAnalystAgent 保持稳定的工具、会话存储和 UsageMeter，LlmRuntimeManager 管理可替换的聊天、
plain 和压缩执行资源。聊天取得应用级 gate 后固定一轮租约；独立摘要在任务开始时取得租约。旧模型
在最后一个使用者离开后释放，模型切换不顺带应用待重启参数。缺少模型配置时，本地服务继续运行，
Wiki LLM 工作保持可重试，填写密钥后恢复。关闭先拒绝新的配置与模型工作，再等待租约退出并释放资源。

GET /desktop/config/effective 提供脱敏的已保存值、来源、实际运行值和各组件应用结果；raw 配置接口
继续用于受保护的原文编辑。LLM 可热更新的键、其它键的重启策略由 ConfigPolicy 统一声明。
本期不监听外部配置文件修改，也不热更新 Embedding 客户端或迁移索引。

## 7. 发布结构

```text
dist/
├── SelfAnalyst.exe
├── self-analyst-app.jar
└── data/                       # 首次运行或既有数据

dist-portable/
├── SelfAnalyst.exe
├── self-analyst-app.jar
├── runtime/                    # jlink JRE
└── data/

artifacts/
├── SelfAnalyst-portable.zip
├── SelfAnalyst-portable.zip.sha256
├── SelfAnalyst_0.2.0_x64-setup.exe
└── SelfAnalyst_0.2.0_x64-setup.exe.sha256
```

NSIS 安装包把后端 JAR、jlink runtime 和安装布局标记作为 Tauri resource 安装。桌面壳识别该标记后，
从 resource 目录启动后端，但把工作目录切换到当前用户应用数据目录；因此应用升级或卸载不会把用户
数据库与配置当作安装文件处理。便携包仍以可执行文件目录作为工作目录。

accessibility sidecar 随内容模块资源打包并按平台释放。所有发布结构都没有 `tools/PaddleOCR-json`、
`tools/whisper` 或声音模型。

## 8. 验证

- Java：`mvn test`
- Rust sidecar：`cargo test --manifest-path self-analyst-axsidecar/Cargo.toml`
- 便携发布：`.\scripts\build-portable.ps1`
- NSIS 发布：`.\scripts\build-installer.ps1` 后运行 `.\scripts\check-installer.ps1`
- 可执行 JAR：`.\scripts\check-packaged-jar.ps1`
- 数据边界：内容策略、迁移、标题提取和配置墓碑的自动化测试
