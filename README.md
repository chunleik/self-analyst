# SelfAnalyst

**English** | [简体中文](README.zh-CN.md)

[官网源码](website/en/index.html) · [官网预览与发布说明](docs/website.md)

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

Desktop Settings opens a dedicated “Model settings” view by default. It supports one
OpenAI-compatible connection, connection presets, a write-only API key field, model discovery
and manual entry, temperature, output limits, and a minimal generation test. After saving,
new chats and summary jobs use the new configuration, while responses already in progress
continue using the previous version. Summaries and compaction retain their low-temperature
policy; sessions and usage counters are not reset. See the [model settings guide](docs/llm-settings.md)
for instructions.

“Advanced configuration” retains the raw text editor for `./data/config/config.toml`. If the
file is missing, it displays a commented template; opening the editor does not write to disk.
The model form updates only the specified keys, preserving other text and comments. Complex
target syntax that cannot be safely updated requires advanced configuration. The legacy generic
structured API and Agent configuration tools may still regenerate the TOML file. Switching
between the two views prompts for confirmation if there are unsaved changes.

Explicit TOML values take precedence over environment variables. Removing an override restores
fallback to environment variables and defaults; an explicitly empty key prevents environment
fallback. Leaving the password field blank keeps the current key. “Clear key” and “Restore key
inheritance” are separate actions, each requiring confirmation. Configuration sources and runtime
status are shown in the interface. Refer to the [user configuration specification](openspec/specs/user-configuration/spec.md)
and `SupportedKeys` for supported keys. An explicit JVM property takes precedence for `memory.dir`;
`events.port` does not accept an environment variable.

The keys that can be updated without restarting are `llm.api-key`, `llm.base-url`, `llm.model`,
`llm.temperature`, and `llm.max-tokens`. Ports, budgets, `maxIters`, compaction thresholds, and the
Embedding client continue using their startup configuration. Changes to a key inherited by the
Embedding client trigger a separate restart notice. Local services can still start without a
configured model; after completing the model settings, new work can resume without restarting.

The generation test sends one fixed, minimal request and may incur a small charge. Neither this
test nor model discovery saves the configuration. The legacy LLM check in advanced configuration
only verifies connectivity to the model catalog. A successful save does not guarantee that the
remote model can be called, and a successful test does not change the runtime configuration.
External file edits are not automatically applied at runtime; save through the application or
restart the backend. For concurrent updates to the same field, the last committed update wins.

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
