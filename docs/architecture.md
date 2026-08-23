# SelfAnalyst 架构文档

## 概述

SelfAnalyst 是一个基于数据的自我提升工具。通过自动采集窗口活动、屏幕内容、音频等多维数据，结合 LLM 分析，实现"感知 → 认知 → 改进"的闭环。

**核心能力**：窗口追踪 + 内容识别 (UIA/OCR) + 音频转录 (whisper.cpp) + Web 仪表板 + 桌面应用。

技术栈：Java 21、AgentScope Java 2.0.1、PaddleOCR-json、whisper.cpp、Tauri 2.x。

---

## 模块架构

6 功能模块 + 1 集成验证模块组 + 1 桌面壳：

```
self-analyst/
├── pom.xml                    (聚合 pom)
│
├── self-analyst-aw/           (AW 引擎 — Java 库)
│   职责: ActivityWatch 兼容 REST API、AQL 查询引擎、窗口/AFK 数据采集、Web UI
│   依赖: Javalin、SQLite、JNA、Jackson
│
├── self-analyst-content/      (内容识别 — Java 库)
│   职责: UIA 树遍历 (PowerShell)、PaddleOCR/Tesseract OCR、截屏、ThinDetector
│   依赖: JNA、Tess4J、Jackson
│
├── self-analyst-audio/        (音频采集 — Java 库)
│   职责: Java Sound API 录音、VAD 静音检测、whisper.cpp 转录
│   依赖: Jackson
│
├── self-analyst-wiki/         (LLM Wiki — Java 库)
│   职责: 多级时间摘要、本地语义索引 (Lucene KNN)、EmbeddingClient
│   依赖: SQLite、Lucene、Jackson、AgentScope (@Tool)
│
├── self-analyst-file/         (目录文件监控 — Java 库)
│   职责: NIO 文件监控、内容提取 (PDF/Office/图片 OCR)、LLM 摘要、语义索引
│   依赖: SQLite、Lucene、PDFBox、POI、Tess4J、复用 wiki 的 EmbeddingClient
│   注: 时间轴 heartbeat 走 HTTP，不直连 self-analyst-aw
│
├── self-analyst-app/          (主应用 — 可执行 jar)
│   职责: server-only 启动、ReActAgent、Memory 系统、模块集成、配置读写工具
│   依赖: 上述库模块 + AgentScope
│
├── self-analyst-integration-test/ (集成验证 — 5 个可执行 jar)
│   职责: 每个子模块启动对应功能模块的真实组件，通过 HTTP/CRUD/API 走完整链路
│   子模块: aw-test, content-test, audio-test, wiki-test, app-test
│   运行: mvn package -f self-analyst-integration-test/pom.xml && java -jar ...-jar-with-dependencies.jar
│
└── self-analyst-desktop/      (桌面程序 — Tauri 壳)
    职责: 单实例保护、WebView2 窗口、系统托盘、Java 后端生命周期管理
    依赖: Tauri 2.x、Rust
```

---

## 数据流

```
                  ┌─────────────────────────────────┐
                  │        ActivityWatch API        │
                  │         (localhost:5700)        │
                  └──────────┬──────────────────────┘
                             │
        ┌──────────────┬─────┴────────┬──────────────┐
        │              │              │              │
  [Window Watcher] [Content Watcher] [Audio Watcher] [File Watcher]
  窗口标题/进程名    UIA 树文本 + OCR   whisper 转录   目录文件变更
        │              │              │              │
        ▼              ▼              ▼              ▼
  aw-watcher-window aw-watcher-content aw-watcher-audio aw-watcher-file
        │              │              │              │
        └──────────────┴──────┬───────┴──────────────┘
                             │
                    ┌────────▼────────┐
                    │   SelfAnalyst   │
                    │   ReActAgent    │
                    │   LLM 分析       │
                    └─────────────────┘
```

文件监控为独立链路：`FileWatcher` 经去抖后向 `aw-watcher-file` 发 heartbeat（HTTP）
并登记 PENDING，`FileIndexWorker` 周期提取内容 → LLM 摘要 → 写入 `FileSemanticIndex`（Lucene KNN），
供 `FileTools` 语义检索。详见 [specs/file.md](specs/file.md)。

---

## Agent 工具集

`SelfAnalystAgent` 注册以下工具供 ReActAgent 调用：

| 工具类 | 主要能力 | 是否可选 |
|--------|---------|---------|
| `ActivityWatchTools` | AW 数据查询（listBuckets、queryEvents、executeAQL） | 必选 |
| `ConfigTools` | 读写 `config.properties`（getConfig、setConfigValue） | 当 UserConfigStore 可用时自动注册 |
| `WikiTools` | LLM Wiki 摘要查询 / 语义检索 | 依赖 WikiStore |
| `FileTools` | 文件摘要语义检索 / 列出 / 状态查询 | 依赖 `file.watch.enabled` |
| Web Search MCP | 联网搜索（Parallel Search） | 依赖 `websearch.enabled` |

用户可在**会话 Tab** 通过自然语言请求 Agent 修改配置，如"把模型改成 gpt-4o-mini"或"关闭联网搜索"，Agent 调用 `setConfigValue` 写入文件，并告知是否需要重启生效。

---

## 配置项

```properties
# LLM
llm.api-key=${OPENAI_API_KEY}
llm.base-url=https://api.openai.com/v1
llm.model=gpt-4o

# ActivityWatch
aw.mode=embedded              # embedded | external
aw.port=5700
aw.data-dir=./data/aw-data

# OCR 引擎
aw.ocr.engine=auto            # paddle | tesseract | auto

# 音频采集
aw.audio.enabled=false        # 需要麦克风

# 文件监控（被监控目录的文件内容会发送给 LLM，注意隐私）
file.watch.enabled=false
file.watch.paths=             # 逗号分隔绝对路径，可多目录

# Memory
memory.dir=./data/memory
```

---

## 构建与发布

```bash
# 一键构建 dist/
powershell -File scripts/build-dist.ps1

# 产物
dist/
├── SelfAnalyst.exe           # 桌面程序
├── self-analyst-app.jar      # Java 后端
└── tools/                    # PaddleOCR + whisper
```

桌面程序启动后会加载 `http://localhost:5700/desktop-ui/` 作为默认窗口内容。
完整 Web UI（aw-webui，MPL-2.0）不随仓库分发，桌面端不提供其入口；缺失时 `http://localhost:5700/` 返回 404。
Windows 上桌面端使用命名 mutex 保证单实例运行；重复启动只提示程序已在运行，不会再创建托盘图标或后端进程。

---

## 技术栈

| 组件 | 技术 |
|------|------|
| LLM Agent | AgentScope Java 2.0.1 + Project Reactor |
| HTTP 服务器 | Javalin (Jetty) |
| 数据存储 | SQLite (AW 使用 aw.db 单库) |
| 窗口追踪 | JNA → user32.dll |
| 内容识别 | UIAutomation (PowerShell) + PaddleOCR-json + Tess4J |
| 音频转录 | Java Sound API + whisper.cpp |
| 桌面应用 | Tauri 2.x (Rust + WebView2) |
