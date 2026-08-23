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
- 7-Zip (运行 `download-tools.ps1` 解压 PaddleOCR 时需要)
- 桌面端额外需要：Rust stable、Node.js、MSVC + Windows SDK（详见下方“桌面端开发”）

嵌入式 AW 模式下无需安装 Python 或 ActivityWatch，所有组件由 Java 实现。

> **注意**：OCR / 音频转写依赖的二进制（PaddleOCR、whisper.cpp 及模型，约 700MB）
> 不随仓库分发。clone 后请先运行 `download-tools.ps1` 拉取到 `tools/`，
> 否则相关功能不可用、`build-dist.ps1` 也不会打包这些工具。

## 快速开始

```bash
# 设置 API Key
export OPENAI_API_KEY=sk-your-key
# 或编辑 src/main/resources/application.properties

# 首次构建前：下载外部工具（PaddleOCR + whisper.cpp + 模型，约 700MB）
powershell -File scripts/download-tools.ps1

# 构建完整 dist/
powershell -File scripts/build-dist.ps1

# 启动后台服务（在 http://localhost:5700 提供 REST API 与桌面页）
java -jar dist/self-analyst-app.jar

# 或桌面模式（双击）
.\dist\SelfAnalyst.exe
```

> `java -jar` 只启动后台服务，本身不提供命令行交互；启动后请用桌面端
> 或浏览器访问 `http://localhost:5700/desktop-ui/` 与 Agent 对话。

桌面模式会启动同目录下的 `self-analyst-app.jar`，并在窗口中加载轻量桌面页
`http://localhost:5700/desktop-ui/`。右键托盘图标可使用：

| 菜单项 | 行为 |
|--------|------|
| 显示窗口 | 显示并聚焦桌面窗口 |
| Web版桌面 | 用系统默认浏览器打开 `http://localhost:5700/desktop-ui/` |
| 关于 | 显示版本、桌面壳和后端服务信息 |
| 退出 | 终止 Java 后端并退出桌面程序 |

> **关于完整 Web 仪表盘**：`http://localhost:5700/` 提供的完整仪表盘使用
> [ActivityWatch aw-webui](https://github.com/ActivityWatch/aw-webui)（MPL-2.0）
> 的预编译产物，**不随本仓库分发**，因此桌面端/前端不提供其入口。轻量桌面页
> `/desktop-ui/`、CLI 和全部 REST 接口不依赖它；缺失时该路由返回 404，其余功能不受影响。
> 如需完整仪表盘，可自行构建 aw-webui 并放入 `self-analyst-aw/src/main/resources/webui/`，
> 然后直接在浏览器访问 `http://localhost:5700/`。

桌面端为 Windows 单实例运行；重复双击 `SelfAnalyst.exe` 时会提示
`SelfAnalyst 已在运行`，不会再创建额外托盘图标或后端进程。

在桌面页 / Web 桌面的对话框里直接输入问题即可：

```
我今天的时间都花在哪里了？
帮我对比一下本周和上周的专注时间
我想把社交媒体的时间减少到每天 30 分钟以内
```

## 配置

所有配置在 `application.properties` 或通过环境变量设置：

| 属性 | 环境变量 | 默认值 | 说明 |
|------|---------|--------|------|
| `llm.api-key` | `OPENAI_API_KEY` | - | LLM API 密钥 |
| `llm.base-url` | `LLM_BASE_URL` | `https://api.openai.com/v1` | API 地址 |
| `llm.model` | `LLM_MODEL` | `gpt-4o` | 模型名称 |
| `aw.mode` | `AW_MODE` | `embedded` | AW 模式：`embedded` 或 `external` |
| `aw.port` | `AW_PORT` | `5700` | AW 服务端口 |
| `aw.data-dir` | `AW_DATA_DIR` | `./data/aw-data` | 活动数据存储目录（相对启动目录；可改为 `${user.home}/.self-analyst/aw-data`） |
| `memory.dir` | `MEMORY_DIR` | `./data/memory` | 目标/模式/记忆存储目录（相对启动目录；可改为 `${user.home}/.self-analyst`） |
| `aw.ocr.engine` | `AW_OCR_ENGINE` | `auto` | OCR 引擎：`auto`、`paddle`、`tesseract` |
| `aw.audio.enabled` | `AW_AUDIO_ENABLED` | `false` | 音频采集（需麦克风） |
| `file.watch.enabled` | `FILE_WATCH_ENABLED` | `false` | 目录文件监控 + LLM 摘要（内容会发送给 LLM，注意隐私） |
| `file.watch.paths` | `FILE_WATCH_PATHS` | - | 监控目录，逗号分隔绝对路径 |

### AW 模式

- **embedded**（默认）：使用内嵌 Java AW 服务器，自动采集窗口和 AFK 数据，零外部依赖
- **external**：连接已有的 Python/Rust ActivityWatch 实例（端口 5600）

```properties
aw.mode=external
aw.base-url=http://localhost:5600/api/0
```

### 内容采集

启动时自动开启三层窗口内容识别：

| 层 | 机制 | 说明 |
|----|------|------|
| UIA 树 | Windows UIAutomation (PowerShell) | 直接读 UI 控件文本，零开销 |
| OCR 兜底 | PaddleOCR-json / Tesseract | UIA 内容不足时截屏识别 |
| 混合判断 | ThinDetector 启发式 | 自动决定是否需要 OCR |

```properties
# OCR 引擎选择
aw.ocr.engine=auto     # auto = PaddleOCR 优先，不可用时回退 Tesseract
# aw.ocr.engine=paddle   # 强制 PaddleOCR
# aw.ocr.engine=tesseract # 强制 Tesseract
```

PaddleOCR-json (v1.4.1) 不随仓库分发：运行 `scripts/download-tools.ps1`
后会下载到 `tools/PaddleOCR-json/`（详见「快速开始」）。

## Memory 系统

长期记忆持久化在 `memory.dir`（默认 `./data/memory`）：

- **Goal（目标）**：描述、衡量指标、基线值、目标值、设置日期
- **KnownPattern（已知模式）**：发现的行为模式 + 置信度评分
- **ImprovementLog（改进记录）**：行动 → 结果 → 日期，追踪闭环

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
│       ├── uia/                 UIA 树遍历 (PowerShell)
│       ├── ocr/                 PaddleOCR + Tesseract 引擎
│       ├── thin/                ThinDetector 内容密度启发式
│       └── capture/             截屏 + OCR + 混合合并
│
├── self-analyst-audio/           (音频采集模块 — 可独立复用)
│   └── src/main/java/.../audio/
│       ├── AudioWatcher.java    音频采集主循环
│       ├── WhisperEngine.java   whisper.cpp 本地转录
│       ├── AudioCapturer.java   Java Sound API + VAD 静音检测
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
│       ├── extractor/           PDF/Office/图片 OCR/文本 提取器
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

SDD (Specification-Driven Development) 规格文档:

- [docs/architecture.md](docs/architecture.md) — 系统架构
- [docs/specs/core.md](docs/specs/core.md) — App 模块 spec
- [docs/specs/content.md](docs/specs/content.md) — 内容识别模块 spec
- [docs/specs/audio.md](docs/specs/audio.md) — 音频采集模块 spec
- [docs/specs/file.md](docs/specs/file.md) — 目录文件监控模块 spec
- [docs/specs/desktop.md](docs/specs/desktop.md) — Tauri 桌面壳 spec
- [docs/specs/desktop-chat-tab.md](docs/specs/desktop-chat-tab.md) — 会话 tab spec
- [docs/README.md](docs/README.md) — 文档导航索引

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
mvn test                    # 运行测试 (38 tests)
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
| 音频转写（whisper） | ✅ | ⚠️ 引擎逻辑跨平台，但工具路径写死 `.exe`，需替换 mac/linux 版二进制并调整路径 |

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
- [Javalin](https://javalin.io) — 嵌入式 HTTP 服务器
- [picocli](https://picocli.info) — CLI 框架
- SQLite + JDBC — 活动数据存储
- JNA — 原生窗口/AFK 追踪，UIAutomation COM 调用
- PaddleOCR-json — 中文 OCR 识别引擎
- Tess4J — Tesseract OCR 引擎 (回退方案)
- whisper.cpp — 本地语音转文字引擎
- Tauri 2.x — 桌面应用壳 (WebView2 + 系统托盘)
- Jackson — JSON 序列化
- Project Reactor — 异步编排

## 隐私与安全

SelfAnalyst 会采集你的数字活动（窗口、可选的屏幕 OCR / 音频 / 文件内容），权限较高。
我们坚持透明：

- 活动数据、OCR/转写结果、记忆**默认只存本地**；音频与文件监控**默认关闭**。
- 音频转写由本地 whisper.cpp 完成，**音频本身不联网**；OCR 默认只读窗口顶部标题条。
- 仅在对应功能开启时，相关**文本**才会发送到你**自行配置**的 LLM / Embedding / 搜索服务。
- 本地服务仅绑定 `127.0.0.1`，不对外暴露。

完整说明见 [PRIVACY.md](PRIVACY.md)；漏洞报告流程见 [SECURITY.md](SECURITY.md)。

## License

Apache License 2.0 — 详见 [LICENSE](LICENSE)。

本项目依赖/捆绑的第三方组件及其各自协议见 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)。
