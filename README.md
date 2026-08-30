# SelfAnalyst

基于 ActivityWatch 数据的自我提升伙伴。通过 LLM 驱动的智能代理分析你的数字行为，帮助你在三个层次上持续改进：感知 → 认知 → 改进。

## 工作原理

```
活动数据采集          智能分析              持续改进
(窗口/AFK 追踪)  →  (LLM ReAct Agent)  →  (目标 → 洞察 → 行动)
```

1. **感知** — 自动采集窗口活动和空闲状态，呈现客观事实
2. **认知** — Agent 发现行为模式，对比基线，识别值得关注的信号
3. **改进** — 给出具体可执行的建议，追踪每次改进的效果，形成闭环

## 前置要求

- Java 21+
- Maven 3.x (仅从源码构建时需要)
- Node.js 20+（从源码运行 `mvn test` 时需要；仅使用内置 test runner，无需 npm install）
- 7-Zip（仅下载可选 PaddleOCR 增强包时需要）
- 桌面端额外需要：Rust stable、Node.js、MSVC + Windows SDK（详见下方“桌面端开发”）

嵌入式 AW 模式下无需安装 Python 或 ActivityWatch，所有组件由 Java 实现。

> **注意**：核心版不依赖 OCR 或音频二进制。PaddleOCR 屏幕识别增强包和
> whisper.cpp 音频转写工具均按需下载，默认发布包不包含 PaddleOCR。

## 快速开始

```bash
# 设置 API Key
export OPENAI_API_KEY=sk-your-key
# 也可在首次启动后通过桌面配置编辑器写入用户级 config.toml

# 构建核心 dist/（UIA 内容采集，不包含 PaddleOCR）
powershell -File scripts/build-dist.ps1

# 启动后台服务（在 http://localhost:5700 提供 REST API 与桌面页）
java -jar dist/self-analyst-app.jar

# 或桌面模式（双击）
.\dist\SelfAnalyst.exe
```

> `java -jar` 只启动后台服务，本身不提供命令行交互；启动后请用桌面端或浏览器访问
> `http://localhost:<aw.port>/desktop-ui/` 与 Agent 对话，默认 `aw.port=5700`。

桌面模式会启动同目录下的 `self-analyst-app.jar`，通过临时握手文件取得 Java 实际监听端口，
再加载 `http://localhost:<aw.port>/desktop-ui/`。桌面 API 使用每次启动生成的临时 token 认证。
右键托盘图标可使用：

| 菜单项 | 行为 |
|--------|------|
| 显示窗口 | 显示并聚焦桌面窗口 |
| Web版桌面 | 通过携带本次启动临时 token 的会话链接，在系统默认浏览器打开当前端口的桌面页 |
| 关于 | 显示版本、桌面壳和后端服务信息 |
| 退出 | 终止 Java 后端并退出桌面程序 |

> **关于完整 Web 仪表盘**：`http://localhost:<aw.port>/` 提供的完整仪表盘使用
> [ActivityWatch aw-webui](https://github.com/ActivityWatch/aw-webui)（MPL-2.0）
> 的预编译产物，**不随本仓库分发**，因此桌面端/前端不提供其入口。轻量桌面页
> `/desktop-ui/`、CLI 和全部 REST 接口不依赖它；缺失时该路由返回 404，其余功能不受影响。
> 如需完整仪表盘，可自行构建 aw-webui 并放入 `self-analyst-aw/src/main/resources/webui/`，
> 然后直接在浏览器访问实际配置端口的根路径。

桌面端为 Windows 单实例运行；重复双击 `SelfAnalyst.exe` 时会提示
`SelfAnalyst 已在运行`，不会再创建额外托盘图标或后端进程。

在桌面页 / Web 桌面的对话框里直接输入问题即可：

```
我今天的时间都花在哪里了？
帮我对比一下本周和上周的专注时间
我想把社交媒体的时间减少到每天 30 分钟以内
```

## 配置

用户覆盖配置统一写入 `{memory.dir}/config.toml`；下表列出的环境变量仅适用于保留环境变量入口的配置项：

| 属性 | 环境变量 | 默认值 | 说明 |
|------|---------|--------|------|
| `llm.api-key` | `OPENAI_API_KEY` | - | LLM API 密钥 |
| `llm.base-url` | `LLM_BASE_URL` | `https://api.openai.com/v1` | API 地址 |
| `llm.model` | `LLM_MODEL` | `gpt-4o` | 模型名称 |
| `agent.compaction.enabled` | `AGENT_COMPACTION_ENABLED` | `true` | 启用事务保护的 AgentState 长会话压缩 |
| `agent.compaction.triggerMessages` | `AGENT_COMPACTION_TRIGGER_MESSAGES` | `30` | 达到该消息数触发压缩；`0` 关闭该触发器 |
| `agent.compaction.triggerTokens` | `AGENT_COMPACTION_TRIGGER_TOKENS` | `60000` | 达到估算 token 数触发压缩；`0` 关闭该触发器 |
| `agent.compaction.keepMessages` / `keepTokens` | `AGENT_COMPACTION_KEEP_MESSAGES` / `AGENT_COMPACTION_KEEP_TOKENS` | `10` / `12000` | 压缩后保留的近期原文预算；单 message 阈值按消息保留，单 token 阈值保证 token 窗口小于触发值 |
| `aw.mode` | `AW_MODE` | `embedded` | AW 模式：`embedded` 或 `external` |
| `aw.port` | — | `5700` | AW 服务端口；桌面壳与 Java 后端统一从 `config.toml` 获取，修改后需重启 |
| `aw.data-dir` | `AW_DATA_DIR` | `./data/aw-data` | 活动数据存储目录（相对启动目录；可改为 `${user.home}/.self-analyst/aw-data`） |
| `memory.dir` | `MEMORY_DIR` | `./data/memory` | 目标/模式/记忆存储目录（相对启动目录；可改为 `${user.home}/.self-analyst`） |
| `aw.ocr.engine` | `AW_OCR_ENGINE` | `off` | 可选屏幕 OCR：`off`、`auto`、`paddle`、`tesseract` |
| `aw.audio.enabled` | `AW_AUDIO_ENABLED` | `false` | 音频采集总开关 |
| `aw.audio.source` | `AW_AUDIO_SOURCE` | `mic` | `mic`、Windows `system` 或 `both` |
| `aw.audio.engine` | `AW_AUDIO_ENGINE` | `auto` | `auto`、`local-whisper` 或 `cloud-asr`；`auto` 优先可用云端 ASR |
| `aw.audio.model` | `AW_AUDIO_MODEL` | `gpt-4o-transcribe` | 云端 ASR 模型 |
| `aw.audio.chunkSeconds` | `AW_AUDIO_CHUNK_SECONDS` | `10` | 每个音频片段秒数 |
| `wiki.enabled` | `WIKI_ENABLED` | `false` | 对话式历史复盘；开启后生成多级时间摘要，相关文本会发送给 LLM |
| `wiki.backfill.enabled` | `WIKI_BACKFILL_ENABLED` | `false` | 启动时补算最近 7 天；关闭后仍持续生成新结束的时间块 |
| `wiki.semantic.enabled` | `WIKI_SEMANTIC_ENABLED` | `true` | 允许 Wiki 语义检索；还需同时开启 Embedding |
| `embedding.enabled` | `EMBEDDING_ENABLED` | `false` | 生成本地 Lucene 语义索引所需的远程 Embedding 请求 |
| `file.watch.enabled` | `FILE_WATCH_ENABLED` | `false` | 目录文件监控 + LLM 摘要（内容会发送给 LLM，注意隐私） |
| `file.watch.paths` | `FILE_WATCH_PATHS` | - | 监控目录，逗号分隔绝对路径 |

内嵌 AW 模式下，内部 API 地址由 `aw.port` 自动派生；`aw.base-url` 仅在外部 AW 模式下生效。

### AW 模式

- **embedded**（默认）：使用内嵌 Java AW 服务器，自动采集窗口和 AFK 数据，零外部依赖
- **external**：连接已有的 Python/Rust ActivityWatch 实例（端口 5600）

```toml
[aw]
mode = "external"
base-url = "http://localhost:5600/api/0"
```

### 对话式历史复盘

在桌面配置页的 `config.toml` 中显式开启 Wiki；个人首次启用建议补算最近 7 天：

```toml
[wiki]
enabled = true
backfill.enabled = true
semantic.enabled = false
```

重启后，后台会低速生成 `HOUR → MONTH` 多级摘要，Agent 可直接回答“昨天做了什么”或“本周和上周有什么变化”。历史补算完成后无需依赖重启：Worker 每轮都会发现新结束的时间块；即使某轮延迟，也会从上次成功游标继续补齐。`semantic.enabled=false` 不影响按时间复盘，只关闭模糊主题检索。

如需“最近什么时候处理过配置问题”这类语义检索，再配置并开启 `[embedding] enabled = true`。启用 Wiki 会把裁剪后的窗口标题和可选屏幕内容发送给自行配置的 LLM，请先确认供应商与隐私策略。

### 内容采集

窗口内容采集默认仅使用 UIA；OCR 是需要截图权限的可选增强：

| 层 | 机制 | 说明 |
|----|------|------|
| 无障碍树 | 常驻 Rust accessibility sidecar（Windows UIAutomation） | 默认路径，读取可访问控件文本 |
| OCR 增强（可选） | PaddleOCR-json / Tesseract | 显式启用后，仅在 UIA 内容不足时截屏识别 |
| 混合判断 | ThinDetector 启发式 | OCR 启用时决定是否需要截图 |

```toml
[aw.ocr]
engine = "off" # 默认关闭，不截图
```

启用 PaddleOCR 增强时，先显式下载可选包并修改配置：

```powershell
powershell -File scripts/download-tools.ps1 -SkipWhisper -WithOcr
```

然后将 `engine` 设置为 `paddle` 或 `auto`。PaddleOCR-json v1.4.1 会下载到
`tools/PaddleOCR-json/`；默认 `build-dist.ps1` 仍不会携带它，发布含 OCR 的包需传
`-WithOcr`。

## Memory 系统

长期记忆持久化在 `memory.dir`（默认 `./data/memory`）：

- **Goal（目标）**：描述、衡量指标、基线值、目标值、设置日期
- **KnownPattern（已知模式）**：发现的行为模式 + 置信度评分
- **ImprovementLog（改进记录）**：行动 → 结果 → 日期，追踪闭环
- **聊天正文**：以 `{memory.dir}/chat-sessions/chat.db` 单一 SQLite 数据库（WAL 模式）保存会话与消息；会话与消息 ID 由本机后端生成；旧版分片存储在首次启动时自动迁移并归档到 `chat-sessions/legacy/`
- **资源边界**：请求体、opaque 上下文、建议任务和单会话序列化体积均由服务端限额；前端采用服务端 canonical 消息并镜像完整-turn retention
- **崩溃恢复与分页**：每次写操作在单个 SQLite 事务内提交，崩溃恢复由 WAL 保证；会话列表/搜索按 50 条游标分页，搜索走 FTS5 trigram 索引（短查询回退 LIKE）
- **删除一致性与单 writer**：`pending_deletions` 表记录持久删除意图，保证 AgentState 与正文最终同时删除；DesktopServer 以 `.writer.lock` 阻止同一数据目录被两个进程并发写入
- **Agent 会话状态**：模型历史按桌面聊天会话隔离，存于 `{memory.dir}/agent-state/self-analyst-chat/desktop/<sessionId>/`，重启后自动恢复
- **长会话压缩**：达到 message/token 阈值后把旧前缀滚动写入 `AgentState.summary`，仅保留近期原始消息；摘要失败不会覆盖旧历史

聊天正文库表是 UI transcript 的权威，AgentState 是模型执行历史的权威，前端不会把 transcript 每轮重复塞回 prompt。升级前已经存在于服务端、但尚无 AgentState 的会话，会在下一次发送时把当前 user 之前的有效 user/assistant 历史单次懒迁移到 AgentState；旧 WebView 会话数据不参与该迁移。

Agent 每次对话前自动加载记忆，对话后自动保存新的发现。

## 项目架构

多模块 Maven 项目：

```
self-analyst/
├── pom.xml                      (聚合 pom)
├── self-analyst-aw/             (AW 模块 — 可独立复用)
│   └── src/main/java/.../aw/
│       ├── AwServer.java        Javalin HTTP 服务器
│       ├── controller/          14 个 REST 端点
│       ├── store/               SQLite 存储层 (aw.db 单库)
│       ├── query/               AQL 查询引擎 (20 个转换函数)
│       ├── watcher/platform/    窗口/AFK 数据采集器 (JNA 原生)
│       ├── export/              数据导入导出
│       ├── tray/                系统托盘
│       └── settings/            分类规则管理
│
├── self-analyst-content/        (内容识别模块 — 可独立复用)
│   └── src/main/java/.../content/
│       ├── ContentWatcher.java  三层内容采集器
│       ├── uia/                 accessibility sidecar 客户端 + 文本提取
│       ├── ocr/                 可选 PaddleOCR + Tesseract 引擎
│       ├── thin/                ThinDetector 内容密度启发式
│       └── capture/             截屏 + OCR + 混合合并
│
├── self-analyst-axsidecar/      (Rust 无障碍树边车)
│   └── src/main.rs              常驻 JSONL 协议 + Windows UIAutomation
│
├── self-analyst-audio/           (音频采集模块 — 可独立复用)
│   └── src/main/java/.../audio/
│       ├── AudioWatcher.java    音频采集主循环
│       ├── WhisperEngine.java   whisper.cpp 本地转录
│       ├── CloudAudioEngine.java OpenAI-compatible 云端 ASR
│       ├── AudioCapturer.java   麦克风/WASAPI + VAD 静音检测
│       └── AudioEngine.java     STT 引擎接口
│
├── self-analyst-wiki/            (LLM Wiki 模块 — 可独立复用)
│   └── src/main/java/.../wiki/
│       ├── WikiWorker.java      多级时间摘要生成
│       ├── WikiStore.java       SQLite 摘要存储
│       └── semantic/            Lucene KNN 语义索引 + EmbeddingClient
│
├── self-analyst-file/            (目录文件监控模块 — 可独立复用)
│   └── src/main/java/.../file/
│       ├── FileWatcher.java     NIO 监控 + 去抖 + heartbeat
│       ├── FileIndexWorker.java 内容提取 → LLM 摘要 → 索引
│       ├── extractor/           PDF/Office/文本与元数据提取器
│       ├── semantic/            Lucene KNN 语义索引
│       └── FileTools.java       Agent 文件检索工具
│
├── self-analyst-desktop/         (Tauri 桌面壳 — Rust)
│   ├── src-tauri/               Tauri 2.x Rust 后端
│   │   └── src/lib.rs           单实例 + 窗口 + 托盘 + Java 子进程管理
│   └── package.json
│
└── self-analyst-app/            (App 模块 — 主程序 / 后台服务)
    ├── src/main/java/.../
    │   ├── App.java             程序入口（启动 AppSession 后台服务）
    │   ├── AppSession.java      组装并启动各模块 + HTTP 服务
    │   ├── config/Config.java   配置加载
    │   ├── agent/               ReActAgent + Middleware
    │   ├── desktop/             桌面 REST API + 桌面 UI 前端
    │   ├── tools/               Agent 工具集（AW / 文件 / 搜索等）
    │   ├── usage/               LLM token 计量与预算
    │   └── memory/              成长档案: Goal + Pattern + Log
    └── target/self-analyst-app-1.0.0.jar   (fat jar)
```

## 文档

完整架构、模块规格、功能规格和归档入口见 [docs/README.md](docs/README.md)。

## 安装脚本

Hermes-style 一键安装/卸载：

```bash
# Linux/macOS
bash scripts/install.sh
~/.self-analyst/bin/uninstall.sh

# Windows
.\scripts\install.ps1
& "$env:USERPROFILE\.self-analyst\bin\uninstall.ps1"
```

安装后通过 `~/.self-analyst/bin/self-analyst.sh` 启动。

## 构建

```bash
mvn compile                 # 编译
mvn test                    # 统一运行 Java + desktop UI Node 测试
mvn package -DskipTests     # 生成 fat jar

# 一键构建完整 dist/（jar + exe + tools）
powershell -File scripts/build-dist.ps1
```

### 桌面应用

```bash
# 开发模式（需要 Rust + Node.js）
cd self-analyst-desktop && pnpm install && pnpm tauri dev

# 发布构建（包含在 build-dist.ps1 中）
cargo build --release --manifest-path self-analyst-desktop/src-tauri/Cargo.toml

# 托盘/单实例静态回归检查
powershell -File scripts/check-desktop-tray.ps1
```

前置：Rust stable、Windows SDK 10.0.22621、MSVC。

### Windows 免安装版

免安装版会将桌面程序、Java 后端和精简 Java 21 运行时打包，目标机器无需另外安装 Java。
核心精简版无需下载外部 OCR/音频工具：

```powershell
# 核心版：无音频模型、无 PaddleOCR
.\scripts\build-portable.ps1 -Variant minimal
```

如需可选能力，先运行 `download-tools.ps1`（Whisper）或增加 `-WithOcr`（PaddleOCR），
构建含 OCR 的包时同样向 `build-portable.ps1` 传入 `-WithOcr`。带 OCR 的 ZIP 文件名包含
`-ocr` 后缀。

也可以只生成指定版本：

```powershell
# 精简版：不包含 Whisper 语音模型，体积较小
.\scripts\build-portable.ps1 -Variant minimal

# 完整版：包含 Whisper，支持本地语音转写
.\scripts\build-portable.ps1 -Variant full

# 复用已有的 JAR 和 EXE，仅重新组装免安装包
.\scripts\build-portable.ps1 -SkipBuild -Variant minimal
```

构建产物统一输出到 `artifacts/`，未压缩的组装内容位于 `dist-portable/`：

- `artifacts/SelfAnalyst-portable-minimal.zip`：精简版，不含 Whisper 语音模型
- `artifacts/SelfAnalyst-portable.zip`：完整版，包含 Whisper 语音模型
- `artifacts/SelfAnalyst-portable-minimal-ocr.zip` / `SelfAnalyst-portable-ocr.zip`：显式 `-WithOcr` 构建的可选 OCR 版本
- `dist-portable/`：当前打包内容的未压缩目录

使用时解压整个 ZIP，然后运行 `SelfAnalyst.exe`。不要只复制 EXE；同目录下的
`self-analyst-app.jar` 和 `runtime/` 是运行所需内容，`tools/` 仅承载已选的可选能力。应用数据保存在
解压目录下的 `data/` 中，因此移动或覆盖目录前请先备份该目录。

> 免安装包不内置 WebView2 Runtime，目标 Windows 系统需要已经安装 WebView2。

## 平台支持

项目在 **Windows** 上开箱即用（构建脚本、内置工具、内容识别全部就绪）。
**macOS / Linux 目前是"可编译、可部分运行"，但不是开箱即用**——下面给出当前现状
和需要自己补齐的点，方便非 Windows 用户完善。

### 各层现状

| 层面 | Windows | macOS / Linux |
|------|---------|---------------|
| Java 模块编译 (`mvn package`) | ✅ | ✅ 可编译（`jna-platform` 是跨平台 jar，Windows 专属调用仅在运行时才触发） |
| 桌面端编译 (`cargo build`) | ✅ | ✅ Tauri 2.x 跨平台，自动用 clang + 系统 WebView（mac 为 WKWebView） |
| 官方构建脚本 (`build-dist.ps1` / `download-tools.ps1`) | ✅ | ❌ PowerShell 脚本，且下载的是 Windows 版二进制 |
| 活动追踪（窗口 / AFK） | ✅ | ✅ 已有 `MacWindowTracker` / `LinuxWindowTracker`（mac 需授予**辅助功能**权限） |
| 内容识别（UIA / OCR） | ✅ | ❌ 仅 `WindowsCapture`，非 Windows 下 `ContentWatcher` 直接抛 `UnsupportedOperationException` |
| 音频转写 | ✅ 本地 whisper 或云端 ASR | ⚠️ 云端 ASR 可用；本地 whisper 需提供对应平台二进制 |

### macOS / Linux 上手动构建

绕开 PowerShell，直接用 Maven / Cargo：

```bash
# 1. 编译 + 打包 Java（全平台通用）
mvn package -DskipTests

# 2. 编译桌面壳（需 Rust stable + Node.js；mac 还需 Xcode Command Line Tools）
cd self-analyst-desktop && npm install && npm run tauri build

# 3. 运行 CLI（活动追踪可用，内容/OCR 不可用）
java -jar self-analyst-app/target/self-analyst-app-1.0.0.jar
```

### 若要让 macOS / Linux 完整可用，仍需补齐

1. **构建脚本**：为 `download-tools.ps1` / `build-dist.ps1` 增加 mac/linux 分支，或提供等价的 `*.sh`。
2. **工具二进制与路径**：下载 PaddleOCR / whisper 的 mac/linux 构建，并把代码里写死的
   `tools/.../*.exe`（见 `WindowsCapture.java`、`AudioWatcher.java`）改为按 `os.name` 选择可执行名。
3. **内容识别实现**：新增 `MacCapture` / `LinuxCapture`（实现 `PlatformCapture`），替代 Windows 专属的
   UIAutomation + 截屏逻辑——这是**功能缺口**，非纯构建问题。

> 提示：`scripts/install.sh` 提供了 Linux/macOS 的一键安装，但它只部署 jar、
> 不编译桌面端、也不下载平台工具。

## 技术栈

- Java 21
- [AgentScope Java 2.0.1](https://github.com/agentscope-ai/agentscope-java) — LLM Agent 框架
- [jtokkit](https://github.com/knuddelsgmbh/jtokkit) — 本地 token 计数（tiktoken cl100k_base）
- [Javalin](https://javalin.io) — 嵌入式 HTTP 服务器
- [picocli](https://picocli.info) — CLI 框架
- SQLite + JDBC — 活动数据存储
- JNA — 原生窗口/AFK、截屏与 Windows 音频输入
- Rust `uiautomation` — 常驻 accessibility sidecar 的 Windows UIAutomation 客户端
- PaddleOCR-json — 可选中文屏幕 OCR 识别引擎
- Tess4J — 可选 Tesseract 屏幕 OCR 回退引擎
- whisper.cpp / OpenAI-compatible ASR — 本地或云端语音转文字
- Tauri 2.x — 桌面应用壳 (WebView2 + 系统托盘)
- Jackson — JSON 序列化
- Project Reactor — 异步编排

## 隐私与安全

SelfAnalyst 会采集你的数字活动（窗口、可选的屏幕 OCR / 音频 / 文件内容），权限较高。
我们坚持透明：

- 活动数据库和记忆默认保存在本机；音频与文件监控默认关闭。
- `local-whisper` 不外发音频；`cloud-asr` 和可用的 `auto` 会把 WAV 发送到配置的 ASR 服务。
- 对话、摘要、文件监控、Embedding 和搜索可能把相应内容发送到用户配置的第三方服务。
- 本地服务只绑定 `127.0.0.1`；桌面模式还用每次启动生成的临时 token 保护 `/desktop/*`。

完整说明见 [PRIVACY.md](PRIVACY.md)；漏洞报告流程见 [SECURITY.md](SECURITY.md)。

## 许可证

Apache License 2.0 — 详见 [LICENSE](LICENSE)。

本项目依赖/捆绑的第三方组件及其各自协议见 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)。
