# SelfAnalyst 架构

## 1. 总览

SelfAnalyst 使用 Java 21 Maven 多模块后端、Tauri 桌面壳和 Rust accessibility sidecar。系统以
“事实采集—本地存储—派生分析—Agent 查询”为主链路；内容事实限定为标题，不保存屏幕正文。

```text
Win32 前台窗口 ───────────────┐
Rust UIAutomation（临时树） ──┼─> TitleCapture ─> 内容事件 v2 ─┐
窗口/AFK watcher ────────────────────────────────> window/AFK ─┼─> ActivityWatch ─> Wiki 聚合

用户配置的监控目录 ─> 文件系统元数据采集 ─> file-watch.db ─> FileTools / 桌面 API
                                   └─> metadata-only heartbeat ─> ActivityWatch 通用历史
```

OCR、屏幕截图和声音/语音链路当前不存在。恢复背景见
[archive/removed-features/removed-ocr-audio.md](archive/removed-features/removed-ocr-audio.md)。

## 2. 模块边界

| 模块 | 边界 |
|------|------|
| `self-analyst-aw` | 嵌入式 ActivityWatch 服务、SQLite 事件存储、内容事件策略和迁移 |
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

内容事件 v2 允许 `app`、`title`、可选 `context_title/context_kind`、`title_source`、可选
`title_confidence`、`uia_chars` 和时间元数据。共享写入策略覆盖 HTTP heartbeat/events、导入和
内部存储调用。历史 v1 内容会在 watcher 启动前净化。

Wiki 只能消费标题事实；文件采集器不得读取普通文件正文、计算内容哈希或调用内容摘要/embedding；
只允许通过不跟随链接且有大小/身份校验的入口，在内存中读取监控树内 `.gitignore` 以决定路径是否排除；
规则及其编译后的 matcher 只可保留在进程内缓存，不得进入持久化、日志、错误记录或外发数据。
任何日志、异常、失败记录或备份都不得绕开相应规格保存原始输入。

## 5. 生命周期

`AppSession` 的主要顺序是：加载配置 → 启动 AW → 执行内容历史迁移 → 启动窗口/AFK 与标题 watcher
→ 启动 Wiki/文件/Agent → 启动桌面服务。关闭时按依赖反序停止 watcher、派生服务和 AW。

不存在声音 watcher、声音控制器或 OCR 引擎生命周期。

## 6. 配置兼容

`SupportedKeys` 是现行配置白名单。`DeprecatedKeys` 保存已移除 OCR/声音键的墓碑：旧文件加载时
静默忽略，桌面配置响应不暴露，结构化保存不写回。此兼容层不创建旧模块依赖。

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
└── SelfAnalyst-portable.zip
```

accessibility sidecar 随内容模块资源打包并按平台释放。发布结构没有 `tools/PaddleOCR-json`、
`tools/whisper` 或声音模型。

## 8. 验证

- Java：`mvn test`
- Rust sidecar：`cargo test --manifest-path self-analyst-axsidecar/Cargo.toml`
- 便携发布：`.\scripts\build-portable.ps1`
- 数据边界：内容策略、迁移、标题提取和配置墓碑的自动化测试
