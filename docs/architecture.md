# SelfAnalyst 架构文档

## 概述

SelfAnalyst 通过窗口/AFK、可选屏幕内容、音频和文件数据构建个人活动时间线，再由 LLM Agent、
多级 Wiki 摘要和长期记忆形成“感知 → 认知 → 改进”闭环。项目以 Java 21 Maven 多模块为主体，
桌面壳和无障碍边车使用 Rust。

## 模块边界

```text
self-analyst/
├── self-analyst-aw/           ActivityWatch-compatible API、窗口/AFK watcher、aw.db
├── self-analyst-content/      无障碍树客户端与可选 OCR/截图增强
├── self-analyst-axsidecar/    常驻 Rust 无障碍树进程（Windows UIAutomation）
├── self-analyst-audio/        mic/system 音频输入、VAD、本地 whisper/云端 ASR
├── self-analyst-wiki/         多级时间摘要、Embedding 客户端与 Lucene 索引
├── self-analyst-file/         文件监控、正文提取、LLM 摘要与语义索引
├── self-analyst-app/          配置、Agent、记忆、桌面 API 和模块生命周期
├── self-analyst-integration-test/
│                              AW/content/audio/wiki/app 集成验证程序
└── self-analyst-desktop/      Tauri 单实例桌面壳、Java 子进程、认证和窗口/托盘
```

Maven 根 POM 聚合 6 个 Java 功能模块和集成测试模块组。`self-analyst-desktop` 与
`self-analyst-axsidecar` 使用 Cargo 单独构建，再由发布脚本组装。

## 运行时数据流

```text
WindowWatcher ──────────────┐
AfkWatcher ─────────────────┤
Accessibility sidecar → UIA ┤
可选截图 → OCR ─────────────┤
mic/system → VAD → ASR ─────┼→ AW HTTP heartbeat → aw.db
FileWatcher ────────────────┘                         │
                                                      ├→ Summary/Advice
                                                      ├→ WikiWorker
                                                      └→ ReActAgent tools

Desktop UI → /desktop/* → Desktop controllers → chat.db / AgentState / memory.json
```

### 内容采集

Windows 内容采集先把前台窗口句柄交给进程级共享的 `AxSidecarClient`。Rust 边车通过 JSONL 协议
返回 OS 中性的无障碍树；`UiaTreeWalker` 在 Java 侧执行文本提取和密码字段脱敏。边车不存在、
超时或失败时返回空树；仅当用户显式启用 OCR 时才截图降级，不再启动 PowerShell one-shot 进程。

OCR 默认关闭。启用后裁剪窗口顶部标题条，并对稳定画面复用指纹缓存；具体刷新和隐私边界见
[specs/content.md](specs/content.md) 与 [specs/accessibility-sidecar.md](specs/accessibility-sidecar.md)。

### 音频采集

`AudioCaptureManager` 可在运行时启动或停止 mic、Windows system loopback 或两者。VAD 过滤静音后，
`local-whisper` 在本地调用 whisper.cpp，`cloud-asr` 将 WAV 发送到 OpenAI-compatible ASR，`auto`
优先云端再回退本地。音频默认关闭，隐私边界见 [specs/audio.md](specs/audio.md)。

### 文件与 Wiki

`FileWatcher` 去抖后向 AW 写 heartbeat，并把待处理项写入 `file-watch.db`；`FileIndexWorker` 提取正文、
生成摘要，再按配置写入 Lucene 索引。`WikiWorker` 从已完成时间段生成 HOUR 到 MONTH 多级摘要，
不会为高层摘要重新读取全部原始屏幕内容。

## 配置

classpath `application.properties` 只保存开发者默认值。用户覆盖统一写入
`{memory.dir}/config.toml`；旧 `{memory.dir}/config.properties` 在首次启动时迁移为 TOML 并重命名
为 `.bak`。主要优先级为：

1. 支持该入口的环境变量；
2. 用户级 `config.toml`；
3. 尚未迁移的用户级 `config.properties`；
4. legacy 用户配置；
5. classpath 默认；
6. 硬编码兜底。

`aw.port` 是例外：它不接受环境变量覆盖，只由用户 TOML 或默认值决定。`ConfigTools` 和桌面 raw
编辑器都通过 `UserConfigStore` 读写 `config.toml`。完整契约见 [specs/config-toml.md](specs/config-toml.md)。

## Agent 与持久化

`SelfAnalystAgent` 基于 AgentScope ReActAgent，按需注册 AW、配置、Wiki、文件和网络搜索工具。
聊天包含两套用途不同的权威数据：

- `{memory.dir}/chat-sessions/chat.db`：UI transcript、会话元数据、FTS5 搜索和删除意图；
- `{memory.dir}/agent-state/self-analyst-chat/desktop/<sessionId>/`：模型执行历史、近期上下文和滚动摘要。

会话写入使用 SQLite WAL 事务；旧分片首次迁移到 `chat.db` 后移动到 `chat-sessions/legacy/`。
删除先在 `pending_deletions` 写持久意图，再清理 AgentState 和 transcript，启动时会继续未完成删除。

## 桌面启动与安全边界

Tauri 桌面壳负责单实例、托盘、窗口和 Java 子进程：

1. 生成本次启动使用的随机桌面 token 与唯一端口握手文件路径；
2. 通过环境变量启动 `self-analyst-app.jar`；
3. Java 解析 `config.toml`、绑定 `127.0.0.1:<aw.port>`，注册认证生命周期路由；
4. Java 原子发布实际端口；
5. Tauri 使用 token 调用 `/desktop/lifecycle/health`；
6. 健康检查成功后，创建带请求头注入脚本的 WebView 和浏览器会话链接；
7. 退出时调用认证 shutdown 路由，并由受管子进程机制兜底回收 Java。

除 `/desktop/session` 外，桌面模式的 `/desktop/*` 路由要求 token header 或 HttpOnly cookie。
AW-compatible `/api/0`、`/0` 路由不使用桌面 token，但全部受回环绑定和 Host/Origin 校验保护。

## 构建与发布

```powershell
# 按需下载 whisper.cpp；增加 -WithOcr 才下载 PaddleOCR
.\scripts\download-tools.ps1

# 构建 Java、桌面壳、accessibility sidecar 并组装 dist/
.\scripts\build-dist.ps1

# 生成 Windows 免安装包
.\scripts\build-portable.ps1
```

主要产物：

```text
dist/
├── SelfAnalyst.exe
├── self-analyst-app.jar
└── tools/

dist-portable/
├── SelfAnalyst.exe
├── self-analyst-app.jar
├── runtime/
└── tools/

artifacts/
├── SelfAnalyst-portable-minimal.zip
├── SelfAnalyst-portable.zip
├── SelfAnalyst-portable-minimal-ocr.zip  # 仅 -WithOcr
└── SelfAnalyst-portable-ocr.zip          # 仅 -WithOcr
```

## 技术栈

| 领域 | 技术 |
|------|------|
| Agent | AgentScope Java 2.0.1、Project Reactor |
| HTTP | Javalin / Jetty |
| 数据 | SQLite、JDBC、WAL、FTS5、Lucene KNN |
| 原生采集 | JNA、Rust `uiautomation`、WASAPI |
| 内容 | Rust UIAutomation、可选 PaddleOCR-json/Tess4J、PDFBox、Apache POI |
| 音频 | Java Sound、whisper.cpp、OpenAI-compatible ASR |
| 桌面 | Tauri 2.x、WebView2、Deep Chat |
| 配置 | TOML v1.0（tomlj） |
