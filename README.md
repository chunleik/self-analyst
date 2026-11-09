# SelfAnalyst

**English** | [简体中文](README.zh-CN.md)

[Website](https://chunleik.github.io/self-analyst/en/) · [Releases](https://github.com/chunleik/self-analyst/releases)

SelfAnalyst is a local-first tool for analyzing personal activity. It records foreground apps,
window titles, and activity times to help you review your day. Connect a language model to
summarize activities, organize your work history, and ask follow-up questions in chat.

![SelfAnalyst desktop chat interface](docs/mockups/desktop-chat-screenshot-en.png)

## Quick start

The desktop release is currently available for **Windows 10/11**. A macOS package is not yet available.

1. Download the installer or portable ZIP from [GitHub Releases](https://github.com/chunleik/self-analyst/releases).
2. Install the app, or extract the ZIP and run `SelfAnalyst.exe`. Release packages include a Java runtime; no development tools are required.
3. Open Settings in the upper-right corner. In **Model settings**, configure an **API Key** connection, or choose **ChatGPT sign-in** (preview), authorize your account in OpenAI’s browser page, and select a model. Choose **Save and apply**. See the [model settings guide (Chinese)](docs/llm-settings.md).
4. Review activities in **Dashboard**, or ask a question in **Chat**, such as "What did I mainly work on today?"

Good to know:

- Local services start without a configured model; chat and model-generated summaries need a working model connection.
- ChatGPT sign-in requires OpenAI’s supported preview, account eligibility, and direct model authorization. A subscription is not API credit and does not guarantee access. Signing in does not verify model generation; use the optional generation test, which consumes service-side usage. Local automated tests do not establish real-account access.
- Closing the window keeps the app running in the system tray. Use the tray menu to quit completely.
- Runtime data is stored in `%LOCALAPPDATA%\com.selfanalyst.desktop` and survives upgrades. See the [runtime data guide (Chinese)](docs/runtime-storage.md) for portable mode, backup, and recovery.

## Features

If a daily or half-day summary is waiting for another period, the dashboard shows completed child summaries in full, labeled as partial. Expand the entry for incomplete periods, failure reasons, usage, and retry times. When no child summary is available, the entry is labeled as app statistics only; the complete summary replaces it once ready.

For an ended hourly, half-day, or daily summary that failed or exhausted its period budget, use **Repair in chat** beside that exact period. A blocked Yesterday card shows **Repair dependent summary** buttons even when collapsed, with each target's time range. Half-day and daily repairs use completed child summaries; repair unfinished children first. Preview and edit a structured draft, confirm each user-supplied statement, run local validation, and explicitly save. User-confirmed content stays labeled as self-reported; activity statistics and accumulated model usage remain unchanged. Local editing, validation, and saving work without a model or remaining budget. Parent summaries resume through normal scheduling; an exhausted parent can also be repaired locally once its children are ready. See the [repair guide (Chinese)](docs/summary-repair.md).

**Back up before upgrading:** this feature migrates the Wiki database to schema 5. Older releases cannot open the migrated database. Downgrading requires a compatible pre-upgrade backup; never lower the database version manually. See [backup and recovery](docs/runtime-storage.md).

- **Activity review**: Explore current, daily, and historical activities on the dashboard timeline. Available overviews are shown in full without expanding an entry, with topic titles underneath; expand to read each saved topic’s full title and narrative in order, or ask follow-up questions with context. Empty evidence sections are hidden. Local fallback summaries and saved overviews share the same text styling. Expanded entries stay open across automatic refreshes until you collapse them; reading state resets when the activity date or timezone changes. A day runs from 04:00 to 04:00 the next day in local time; see the [activity statistics guide (Chinese)](docs/activity-statistics.md).
- **Personal knowledge**: Connect activity across days and apps to stable projects, topics, and goals in **Knowledge**. Confirm, remove, or correct associations; your decisions survive rebuilding and restarting. Knowledge works locally without extra model calls. See the [personal knowledge guide (Chinese)](docs/personal-ontology.md).
- **Optional Neo4j sync**: Manually publish the current, corrected knowledge graph to a Neo4j database you configure. It is off by default and requires destination, namespace, and data-scope confirmation for every sync. Local storage remains authoritative. See the [setup and read-only query guide (Chinese)](docs/neo4j-sync.md).
- **Images in chat**: Select or paste PNG/JPEG images for a model that supports image input, up to 4 images per turn, each limited to 5 MiB and 20 megapixels. This does not enable background screenshots.
- **Generated files**: Ask for spreadsheets, documents, presentations, or HTML/SVG, then save or download them from file cards in chat. See the [document generation guide (Chinese)](docs/document-generation.md).
- **Connections and tools**: Add HTTPS Streamable HTTP MCP servers with tool review and per-call confirmation, or use built-in read-only QQ/163 mail. Mail credentials default to session-only storage; Windows persistence is opt-in. Mail data requires model-destination consent and may remain in chat history. Mail-origin chats do not automatically create long-term memories. See the [integration](docs/integrations.md) and [mail](docs/mail-integration.md) guides (Chinese).
- **Long-term memory**: Useful information is retained automatically; credentials, sensitive inferences, and one-off activity are filtered out. Ask the assistant in chat to correct or forget a memory.
- **Help and updates**: The **Help** menu provides the user guide, **Documentation**, **Personal knowledge guide**, issue reporting, **Check for updates**, and **Project website** (the GitHub repository, opened in your default browser on desktop). Checks run only when requested; the app never downloads or installs updates automatically. **About SelfAnalyst** shows the installed desktop version and its exact release publication time in your local timezone (including beta releases). It uses cached public metadata when available; if the timestamp cannot be retrieved and no matching cache exists, it shows an unavailable notice. Debug builds show a development label.
- **Interface language**: Chinese and English, following the system language by default. Change it in Settings or with `app.language` (`auto`, `zh`, or `en`), then restart the app.

## Privacy and data boundaries

Activity data is stored locally. Model requests send only the required inputs to the service you
configure. Optional, explicitly confirmed Neo4j sync sends a bounded knowledge-graph snapshot to your
chosen database; it can include private titles, descriptions, and evidence. See the [privacy statement (Chinese)](PRIVACY.md).

- Content events store only allowlisted title fields. Body text, UIA text, control trees, screenshots, OCR, and audio are never stored.
- Failed UIA queries fall back to system window titles. Sensitive applications are excluded from UIA queries.
- File collection is limited to filesystem metadata (names, paths, sizes, and timestamps) in folders you configure. Apart from safely parsing `.gitignore`, it does not read file contents, calculate content hashes, or generate summaries, topics, or vectors.
- Before titles reach the model, private IPv4 addresses, meeting numbers, and account-verification pages are masked, and private-browsing titles are skipped. A private window that exposes no marker cannot be detected; add sensitive applications and sites in **Settings → Privacy**.
- Each application has two modes: **Do not collect** stops new window/content events and UIA queries for that app; **Hide from model** keeps local records but excludes matching events from new model inputs. Both modes hide matching historical events from new model queries, summaries, repair requests, and event projections used for generated documents. Local displays and AFK collection remain unchanged. Uncollected intervals cannot be recovered later.
- Privacy rules apply immediately after saving in the app, including the raw TOML editor. They do not delete existing records or rewrite saved summaries, old tool results, or content already seen in a chat. An in-flight model operation keeps the policy snapshot it started with; the next operation uses the saved policy. Numeric AQL aggregates can still include hidden time. Projection exports report the actual exported `recordCount` and `privacyFilteredCount`; `complete: true` means the filtered projection completed.
- Agent AQL queries cannot use `tag` to overwrite `app`, `title`, `context_title`, `url_host`, or `private_browsing`, which would bypass privacy filtering. Other custom tags and direct local AQL queries remain available.
- The event database stores merged activity intervals and is authoritative; back it up regularly. Collection stops when disk space falls below the blocking threshold; history is never deleted automatically.
- Neo4j configuration saves and status checks never connect to Neo4j. Disabling sync does not erase the remote copy; source removals reach it only on the next successful manual sync. Changing namespaces leaves the old namespace in place.
- ChatGPT OAuth credentials are stored separately from TOML with owner-only filesystem permissions, not application-level encryption or an OS credential vault. Processes running as the same OS user can still access them. Disconnecting clears local tokens and attempts remote revocation; an unconfirmed revocation is reported. Independent API keys and chat history are kept.

## Configuration

Settings has six tabs: **Model settings** (one active API Key or ChatGPT connection, with model
discovery and a generation test), **Privacy** (application modes and hidden sites),
**Advanced configuration** (the raw TOML editor), **Runtime data** (directories, storage usage,
and migration backup cleanup), **Neo4j sync** (optional, manually confirmed graph sharing),
and **Connections and tools** (MCP servers and read-only mail).

In **Privacy**, search recent applications or enter an executable name manually, choose a mode,
and save. Candidates come from the last 30 days of local window activity, with up to 100 entries
ordered by duration. Manual entry remains available if candidates cannot be loaded. Application
names match the whole executable name, ignoring case; add variants such as `PotPlayerMini.exe`
and `PotPlayerMini64.exe` separately. Website entries hide matching titles, context titles, or hosts
from the model and do not stop browser collection.

The underlying keys are `events.collection.excludeApps` (**Do not collect**),
`wiki.privacy.excludeApps` (**Hide from model**), and `wiki.privacy.excludeSites` (hidden sites).
They accept comma- or semicolon-separated values, default to empty, and are not writable by Agent
configuration tools. The model policy combines both application lists; if an app appears in both,
Privacy displays **Do not collect**. All three keys apply without restarting after a successful
save through the app.

Explicit TOML values take precedence over environment variables, which take precedence over defaults.
Changes to `llm.api-key`, `llm.base-url`, `llm.model`, `llm.temperature`, `llm.provider`,
`llm.chatgpt-account`, and `llm.chatgpt-model` apply to new chats and summary jobs without restarting.
ChatGPT uses its own account and model selection, not the API Key connection’s URL or temperature.
Embedding remains a separate API Key connection, may retain its existing `llm.api-key` fallback,
and never receives OAuth tokens or becomes enabled merely by switching providers.
Saved `neo4j.*` settings apply to the next manual sync without
restarting or sending data. Other restart-required keys are identified in Settings. External edits to the TOML
file are not applied until you save through the app or restart the backend.

| Configuration key | Environment variable | Default |
|-------------------|----------------------|---------|
| `llm.base-url` | `LLM_BASE_URL` | `https://api.openai.com/v1` |
| `llm.model` | `LLM_MODEL` | `gpt-4o` |
| `llm.api-key` | `OPENAI_API_KEY` | Empty |
| `llm.provider` | None (`config.toml` only) | `api-key`; alternatively `chatgpt` |
| `llm.chatgpt-account` | None (`config.toml` only) | Empty; selected authorized account ID |
| `llm.chatgpt-model` | None (`config.toml` only) | Empty; select an available model or enter its ID |
| `events.mode` | `EVENTS_MODE` | `embedded` |
| `events.port` | None (`config.toml` only) | `5700` |
| `events.collection.excludeApps` | None (`config.toml` only) | Empty |
| `wiki.privacy.excludeApps` | None (`config.toml` only) | Empty |
| `wiki.privacy.excludeSites` | None (`config.toml` only) | Empty |
| `neo4j.enabled` | None (`config.toml` only) | `false` |
| `neo4j.uri` | None (`config.toml` only) | Empty |
| `neo4j.database` | None (`config.toml` only) | `neo4j` |
| `neo4j.username` | None (`config.toml` only) | `neo4j` |
| `neo4j.password-env` | Names the password environment variable; never the password | `SELF_ANALYST_NEO4J_PASSWORD` |
| `neo4j.namespace` | None (`config.toml` only) | Empty; required, exclusive to one local dataset |
| `neo4j.timeout-seconds` | None (`config.toml` only) | `15` (range: `1`–`120`) |

For Neo4j, set the password in the environment inherited by the app before starting it; never put
the password in TOML or the URI. Changing that process environment requires restarting the app.
Unencrypted connections allow only `bolt://` on `localhost`, `127.0.0.1`, or `[::1]`; remote targets
require `bolt+s://` or `neo4j+s://` with a valid CA-trusted certificate. Plain `neo4j://`, `+ssc`,
embedded credentials, paths (including a trailing slash), queries, and fragments are rejected.
Use a namespace unique to this local dataset. In **Settings → Neo4j sync**, review the scope
and destination, then choose **Confirm and sync…**. The configured account needs graph-write,
constraint-creation, and `SHOW CONSTRAINTS` permissions; setup details and safe Cypher examples are in the [Neo4j guide (Chinese)](docs/neo4j-sync.md).

See the [model settings guide (Chinese)](docs/llm-settings.md) and the
[user configuration specification (Chinese)](openspec/specs/user-configuration/spec.md) for all
supported keys, and the [architecture document (Chinese)](docs/architecture.md) for summary budgets and limits.

## Development

Requirements: JDK 21, Maven 3.9+, Node.js 20+ for desktop development, Rust stable and Cargo for the
Tauri shell and accessibility sidecar, and Windows 10/11 for the full desktop experience.

```powershell
mvn test                                                        # JUnit and desktop UI tests
mvn package -DskipTests                                         # build JARs
cargo test --manifest-path self-analyst-axsidecar/Cargo.toml    # Rust sidecar tests

Set-Location self-analyst-desktop; pnpm install; pnpm tauri dev # run the desktop shell

.\scripts\build-dist.ps1                                        # local distribution
.\scripts\build-portable.ps1                                    # portable ZIP with a jlink JRE
.\scripts\build-installer.ps1                                   # NSIS installer
```

Build output is written to `artifacts/`, with `.sha256` files for the ZIP archive and installer.

| Module | Responsibility |
|--------|----------------|
| `self-analyst-events` | Embedded event service, event policies, and historical data migration |
| `self-analyst-content` | Foreground windows, temporary UIA queries, and context-title extraction |
| `self-analyst-file` | Filesystem metadata within user-configured folders |
| `self-analyst-wiki` | Time-based aggregation, summaries, and indexing of title facts |
| `self-analyst-ontology` | Typed entities, evidence-backed relationships, corrections, and local knowledge queries |
| `self-analyst-app` | Startup orchestration, Agent, and desktop API/UI |
| `self-analyst-axsidecar` | Windows UIAutomation Rust sidecar |
| `self-analyst-desktop` | Tauri desktop shell |

## Documentation

The following documents are maintained in Simplified Chinese:

- [Documentation and specification index](docs/README.md)
- [Architecture](docs/architecture.md)
- [Optional Neo4j sync and read-only queries](docs/neo4j-sync.md)
- [MCP connections and read-only QQ/163 mail](docs/integrations.md)
- [Testing and integration verification](docs/testing.md)
- [Summary quality evaluation](docs/summary-quality-evaluation.md)
- [Website preview and deployment](docs/website.md)
- [0.6.0 release notes](docs/releases/v0.6.0.md)

## Contributing

Bug reports, feature suggestions, and pull requests are welcome. The project follows specification-driven
development, with `openspec/specs/` as the authoritative source of behavior contracts. Read the
[contribution guide](CONTRIBUTING.md) before making changes, and see the
[code of conduct](CODE_OF_CONDUCT.md). Report vulnerabilities privately as described in the
[security policy](SECURITY.md) rather than opening public issues.

## License

This project is licensed under the [Apache License 2.0](LICENSE). See also [NOTICE](NOTICE) and
[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).
