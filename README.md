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

## Configuration

On first startup, the application creates `config.toml` in the data directory. Desktop Settings
can edit currently supported fields. Explicitly saved TOML values take precedence over environment
variables; unset keys fall back to environment variables and then built-in defaults. Removing a
configuration entry restores fallback behavior.

An explicit JVM property for `memory.dir` retains the highest precedence. `events.port` cannot be
overridden by an environment variable. The authoritative list of keys is defined by the
[user configuration specification](openspec/specs/user-configuration/spec.md) and `SupportedKeys`.

After saving any of the following settings through desktop Settings or the Agent configuration
tools, new chat turns and summary jobs use the new values immediately:
`llm.api-key`, `llm.base-url`, `llm.model`, `llm.temperature`, and `llm.max-tokens`.
An ongoing chat turn, including context compaction and tool calls, continues using its original
configuration. Chat history and daily usage counters are not reset. Summaries and compaction
continue to use a low-temperature policy.

The local service can start without an API key. Chat becomes available after a key is configured;
clearing the effective key makes new LLM work report that the model is not configured.

Settings shows saved values, configuration sources, and application status. “Applied to new work;
existing work uses its original configuration” does not require a restart. “Backend restart required”
applies only to the listed components. Ports, storage directories, budget policies, `maxIters`,
compaction thresholds, and Embedding clients still use startup-time configuration. If Embedding
inherits the LLM API key, a key change produces a separate restart notice for Embedding.

Editing TOML in an external editor does not trigger automatic hot reload; save it within the
application or restart the backend to apply the changes. Connection tests use the current editor
text. A successful connection test does not mean the running configuration has changed or guarantee
that the selected model can complete a conversation.

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
