# SelfAnalyst

**English** | [简体中文](README.zh-CN.md)

SelfAnalyst is a local-first tool for analyzing personal activity. A local event service stores
window/AFK activity and in-app context titles for Wiki aggregation. Filesystem metadata from
folders explicitly configured by the user is stored locally, exposed through FileTools and the
desktop API, and retained in local event history through metadata-only heartbeats.

![SelfAnalyst desktop chat interface](docs/mockups/desktop-chat-screenshot.png)

> This screenshot shows the desktop chat interface: the session list, conversation area, and a
> side panel with current status, recent activity, suggested to-dos, long-term memory, and context options.

In embedded event-service mode, every raw event that passes privacy validation is first written
permanently to append-only monthly SQLite partitions organized by UTC. The service then builds a
mergeable, rebuildable `events.db` projection. External ActivityWatch mode does not provide this
permanent-retention guarantee. When free disk space falls below the blocking threshold, new
collection is rejected; old partitions are not automatically deleted.

“Content collection” currently means title collection only. The full UIA control tree may be read
temporarily during recognition, but only system window titles, WeChat conversation names,
article/document/page titles, and metadata such as source, confidence, and timestamps are saved.
UIA body text must not be persisted.

## Requirements

- JDK 21
- Maven 3.9+
- Node.js 20+ for desktop development
- Rust stable and Cargo for the Tauri shell and accessibility sidecar
- Windows 10/11 for the full desktop experience

## Build and test

```powershell
mvn test
mvn package -DskipTests
cargo test --manifest-path self-analyst-axsidecar/Cargo.toml
```

Desktop development:

```powershell
Set-Location self-analyst-desktop
pnpm install
pnpm tauri dev
```

Build a local distribution:

```powershell
.\scripts\build-dist.ps1
```

Build a single portable package with a jlink JRE:

```powershell
.\scripts\build-portable.ps1
```

Build an NSIS installer with the same backend and jlink JRE:

```powershell
.\scripts\build-installer.ps1
```

Output is written to `artifacts/`, with `.sha256` files for the ZIP archive and installer.
Installed distributions keep user data in Tauri's per-user application data directory, preserving
it across application upgrades and uninstallation. Portable distributions keep `data/` next to
the executable. Release packages do not include PaddleOCR, Tesseract, Whisper, or speech models.

## Interface language

Chinese and English are currently available. The default follows the system language, falling
back to English for unsupported languages. Select a language in desktop Settings, or configure it directly:

```toml
[app]
language = "en" # auto, zh, or en
```

The language selection is saved together with other configuration edits. After saving, quit from
the system tray and restart the application. Closing the window only hides it to the tray; it
does not restart the application. If you run the Java backend separately, restart it and refresh
the page. The interface, native menus, and prompts for newly generated content use the same
effective language. Existing conversations, summaries, and user-provided text remain unchanged.

If a configuration draft uses complex TOML syntax and the language field cannot be located
safely, the language selector is disabled; use the raw text editor instead. Before the backend
is ready, the page shows English loading messages, while native startup-error messages follow
the system language.

## Core modules

| Module | Responsibility |
|--------|----------------|
| `self-analyst-events` | Embedded event service, event policies, and historical data migration |
| `self-analyst-content` | Foreground windows, temporary UIA queries, and context-title extraction |
| `self-analyst-file` | Filesystem metadata such as names, paths, sizes, and timestamps within user-configured folders |
| `self-analyst-wiki` | Time-based aggregation, summaries, and indexing of title facts |
| `self-analyst-app` | Startup orchestration, Agent, and desktop API/UI |
| `self-analyst-axsidecar` | Windows UIAutomation Rust sidecar |
| `self-analyst-desktop` | Tauri desktop shell |

## Data boundaries

- Content events allow only the v2 title-field allowlist. `text_content`, `uia_text`, `raw_tree`, body text, and screenshots are not allowed.
- Failed UIA queries fall back to system window titles, without screenshot or OCR fallback.
- Sensitive applications are excluded from UIA queries.
- The file module collects only filesystem metadata: names, paths, sizes, and creation/modification
  times. Apart from safely parsing `.gitignore`, it does not read ordinary file contents, calculate
  content hashes, or generate summaries, topics, or vectors.

See [PRIVACY.md](PRIVACY.md) for privacy details and [docs/README.md](docs/README.md) for the current
specification index. These documents are maintained in Simplified Chinese.

## 配置

桌面设置默认显示独立的“模型设置”：支持一个 OpenAI-compatible 连接、连接预设、只写 API Key、
模型发现与手工输入、温度、输出上限和最小生成测试。保存后新聊天与摘要工作采用新配置，
进行中的回答继续使用旧版本；摘要和压缩保持低温策略，会话及用量不会重置。
操作说明见[模型设置指南](docs/llm-settings.md)。

“高级配置”保留 `./data/config/config.toml` 原文编辑器。文件缺失时显示注释模板，打开本身不写盘。
模型表单只更新指定键并保留其它原文和注释；无法安全修改的复杂目标写法需使用高级配置。
旧通用结构化接口和 Agent 配置工具仍可能重新生成 TOML。两种视图切换时会确认未保存更改。

TOML 显式值优先于环境变量，删除覆盖恢复环境变量及默认值；显式空密钥阻止环境兜底。
密码框留空表示保留当前密钥，“清空密钥”和“恢复密钥继承”是分别确认的操作。
配置来源与运行状态在界面中显示。配置键以[用户配置规格](openspec/specs/user-configuration/spec.md)
和 `SupportedKeys` 为准；`memory.dir` 的显式 JVM property 优先，`events.port` 不接受环境变量。

可热更新的键为 `llm.api-key`、`llm.base-url`、`llm.model`、`llm.temperature`、`llm.max-tokens`。
端口、预算、`maxIters`、压缩阈值及 Embedding 客户端仍使用启动期配置；Embedding 继承密钥变化
会单独提示需重启。未配置模型时本地服务仍可启动，补填后新工作无需重启即可恢复。

生成测试会发送一次固定最小请求，可能产生少量计费；它和模型发现均不保存配置。
高级配置中的旧 LLM 检查只验证目录连接。保存成功不等于远端可调用，测试成功也不改变运行配置。
外部编辑文件不会自动热更新；请通过应用保存或重启后端。相同字段的并发更新以后提交者为准。

| Configuration key | Environment variable | Default |
|-------------------|----------------------|---------|
| `llm.base-url` | `LLM_BASE_URL` | `https://api.openai.com/v1` |
| `llm.model` | `LLM_MODEL` | `gpt-4o` |
| `llm.api-key` | `OPENAI_API_KEY` | Empty |
| `events.mode` | `EVENTS_MODE` | `embedded` |
| `events.port` | None (`config.toml` only) | `5700` |
| `events.collection.title.enabled` | `EVENTS_COLLECTION_TITLE_ENABLED` | `true` |
| `events.raw.dir` | `EVENTS_RAW_DIR` | `{events.data-dir}/raw` |
| `events.raw.query.maxRangeDays` | `EVENTS_RAW_QUERY_MAX_RANGE_DAYS` | `31` |
| `events.raw.query.maxPageSize` | `EVENTS_RAW_QUERY_MAX_PAGE_SIZE` | `1000` |
| `events.raw.lowDisk.warnBytes` | `EVENTS_RAW_LOW_DISK_WARN_BYTES` | `10737418240` |
| `events.raw.lowDisk.blockBytes` | `EVENTS_RAW_LOW_DISK_BLOCK_BYTES` | `1073741824` |
| `events.raw.integrity.startupScope` | `EVENTS_RAW_INTEGRITY_STARTUP_SCOPE` | `latest` |
| `events.raw.projector.batchSize` | `EVENTS_RAW_PROJECTOR_BATCH_SIZE` | `1000` |

The permanent raw-event layer is always enabled in embedded mode. TTL, maximum partition counts,
and automatic deletion are not supported. Once partitions exist, ordinary configuration saves
cannot change `events.raw.dir`; moving the directory requires a separate, explicit data-transfer process.

Legacy OCR keys listed in the [removal notes](docs/archive/removed-features/removed-ocr-audio.md)
and all `aw.audio.*` keys are no longer supported. They do not prevent old configuration files
from loading, but they have no effect.

## Documentation

The following documents are maintained in Simplified Chinese:

- [Architecture](docs/architecture.md)
- [Documentation and specification index](docs/README.md)
- [Testing and integration verification](docs/testing.md)
- [Context-title collection specification](openspec/specs/title-capture/spec.md)
- [Title-minimized persistence specification](openspec/specs/content-event-persistence/spec.md)
- [Temporary removal of OCR and audio modules](docs/archive/removed-features/removed-ocr-audio.md)
- [Privacy](PRIVACY.md)

## Contributing

Bug reports, feature suggestions, and pull requests are welcome. The project follows specification-driven
development, with `openspec/specs/` as the authoritative source of behavior contracts and strict
boundaries for collection and persistence. Read the [contribution guide](CONTRIBUTING.md) before
making changes, especially its sections on data boundaries and the specification-driven workflow.

- [Contribution guide](CONTRIBUTING.md)
- [Code of conduct](CODE_OF_CONDUCT.md)
- [Security policy](SECURITY.md) — Report vulnerabilities privately rather than opening public issues.

## License

This project is licensed under the [Apache License 2.0](LICENSE). See also [NOTICE](NOTICE).
Third-party components used by the project or included in release distributions, along with their
licenses, are listed in [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).
