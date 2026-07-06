# Headroom integration design

Date: 2026-07-06

## Background

SelfAnalyst is a Java 21 multi-module desktop and background service. Its LLM
traffic is already centralized around OpenAI-compatible settings such as
`llm.base-url`, `llm.model`, and `llm.api-key`. The main token-heavy flows are:

- desktop chat through `SelfAnalystAgent`
- desktop summary enhancement through plain completions
- wiki period summarization
- file and semantic retrieval context
- OCR/UIA screen text and audio transcription after they become text

Headroom is a local-first context optimization layer. It can run as an
OpenAI-compatible proxy, a library, or an MCP server, and it also provides
cross-agent memory and failure learning for coding-agent workflows.

## Goals

- Reduce SelfAnalyst LLM token cost by routing eligible chat and summary
  requests through a local Headroom proxy.
- Keep SelfAnalyst functional when Headroom is not installed, not running, or
  temporarily unhealthy.
- Expose clear Headroom status in configuration and through the existing agent
  configuration tools.
- Use Headroom memory and failure learning for this repository's development
  workflow so Codex, Claude Code, Cursor, and similar agents can share project
  facts and learned corrections.
- Keep privacy posture explicit: raw audio is never sent to Headroom, and
  screenshots/images are out of the first implementation unless a later design
  enables multimodal LLM calls.

## Non-goals

- Do not rewrite prompt builders or the Agentscope model integration in the
  first version.
- Do not vendor or reimplement Headroom inside the Java application.
- Do not make Headroom a required runtime dependency.
- Do not proxy speech-to-text audio upload endpoints in the first version.
- Do not add image compression to SelfAnalyst until the product sends images to
  multimodal models intentionally.

## Recommended approach

Use a two-track integration.

Track 1 is runtime token reduction. SelfAnalyst detects a local Headroom proxy
and, when enabled, routes OpenAI-compatible LLM traffic through that proxy. This
uses the existing `llm.base-url` shape and avoids invasive Java-side prompt
compression. If the proxy is unavailable, SelfAnalyst falls back to the configured
provider base URL.

Track 2 is development workflow memory. Repository maintainers use Headroom's
agent wrapping, memory, and `headroom learn` commands outside the shipped app.
The learned facts should be reviewed before they are written into a shared agent
instruction file such as `CLAUDE.md`, `AGENTS.md`, or a local ignored file,
depending on what the installed Headroom version supports and whether the
correction should be shared with the team.

This approach gives immediate token savings and shared development knowledge
without coupling SelfAnalyst's core modules to Headroom internals.

## Runtime architecture

### Configuration

Add Headroom-specific settings:

- `headroom.enabled` / `HEADROOM_ENABLED`, default `false`
- `headroom.proxy-url` / `HEADROOM_PROXY_URL`, default `http://127.0.0.1:8787/v1`
- `headroom.auto-start` / `HEADROOM_AUTO_START`, default `false`
- `headroom.output-shaper` / `HEADROOM_OUTPUT_SHAPER`, default `false`
- `headroom.stats.enabled` / `HEADROOM_STATS_ENABLED`, default `true`

Keep the original provider settings intact:

- `llm.base-url`
- `llm.model`
- `llm.api-key`
- embedding settings

At startup, the effective chat base URL is:

1. `headroom.proxy-url` when `headroom.enabled=true` and the proxy health check
   succeeds.
2. `llm.base-url` otherwise.

The effective embedding base URL stays unchanged in the first version. Embedding
requests are usually smaller, and preserving their current path reduces risk.

### Proxy health

Introduce a small service, tentatively `HeadroomService`, in the app module. It
has one job: resolve Headroom status and effective routing. It should not know
about prompt formats or business data.

Status values:

- `disabled`: user has not enabled Headroom.
- `available`: proxy is enabled and health check succeeds.
- `unavailable`: enabled, but health check failed.
- `fallback`: a previous request failed and SelfAnalyst is using `llm.base-url`.
- `unknown`: status could not be checked.

The health check should be short and local-only. If Headroom exposes a stable
health or stats endpoint, use it. Otherwise, check loopback reachability and keep
failure messages human-readable.

### LLM data flow

Eligible flows:

- `SelfAnalystAgent` chat model
- `SelfAnalystAgent.completePlain`
- wiki summarizer LLM calls that use the agent/plain client
- desktop summary and advice enhancement
- file summary prompts when they share the configured LLM client

Data flow:

```text
SelfAnalyst prompt/context
  -> existing OpenAI-compatible client
  -> effective base URL
     -> Headroom proxy when available
     -> original provider when disabled/unavailable
  -> LLM provider
```

Text content is the first-class target: window titles, OCR/UIA text, audio
transcripts, file excerpts, wiki facts, tool outputs, logs, and retrieved context.
Raw microphone audio, local screenshots, and arbitrary binary files are not routed
through Headroom in the first version.

### Usage and savings

SelfAnalyst's existing `UsageMeter` remains the source of truth for budget
enforcement. It records what the LLM provider or SDK reports after the request.

If Headroom stats are available, show them as a separate informational layer:

- proxy status
- compressed input estimate
- original input estimate
- savings percentage
- output shaping enabled/disabled

Headroom savings must not replace budget enforcement until the exact semantics of
its usage counters are verified against the current Java SDK responses.

## Development workflow memory

### Memory scope

Headroom memory is used for development-agent context, not for SelfAnalyst user
behavior memory. It may store repository facts such as:

- module entry points
- common commands
- CodeGraph usage expectations
- build environment details
- recurring test or packaging gotchas
- preferred local workflow corrections

It must not store private SelfAnalyst activity data, OCR text, audio transcripts,
API keys, or user personal analytics.

### Failure learning

Provide a documented workflow:

- `headroom learn` to preview proposed corrections without writing changes.
- `headroom learn --apply` only after reviewing the generated recommendations.
- If the installed Headroom version writes to `CLAUDE.md` and `MEMORY.md`, copy
  only durable team-wide rules into `AGENTS.md` when they apply to all agents.
- Keep personal preferences or machine-specific fixes in a local ignored file.

The generated changes should be reviewed like code. If a learned correction is
too specific, stale, or contains sensitive data, reject it or move it to a local
ignored file.

### Agent compatibility

Headroom can wrap or share memory across coding agents independently of the
SelfAnalyst runtime. The app should not try to manage those agent wrappers.
Instead, repository docs should explain how to enable them for local development.

## UI and agent tool surface

Expose Headroom in the same configuration surfaces that already show LLM and
budget settings.

Configuration display should include:

- enabled flag
- proxy URL
- auto-start flag
- output shaping flag
- current status
- last health-check error
- last known savings summary, if available

`ConfigTools` should allow reading and updating the Headroom keys, with restart
requirements clearly reported. Changing routing should require backend restart
in the first version, because LLM clients are built during app startup.

The desktop UI can initially present Headroom as a small status row in the LLM or
usage section. A larger dashboard is not required for the first version.

## Error handling

- If Headroom is disabled, behavior is identical to the current app.
- If enabled but unavailable at startup, log a warning and use `llm.base-url`.
- If a Headroom-routed request fails with a local proxy connection error,
  retrying through `llm.base-url` is allowed for non-mutating chat/summary calls.
- If the provider behind Headroom returns an authentication or model error, do
  not hide it as a proxy failure. Surface the original error when possible.
- If savings stats cannot be read, keep LLM calls working and show stats as
  unavailable.
- Never log API keys, raw prompt bodies, OCR text, or audio transcripts as part
  of Headroom diagnostics.

## Testing

Unit tests:

- config parsing for Headroom keys and defaults
- effective base URL resolution
- health status transitions
- `ConfigTools` allowlist and display masking
- fallback behavior on connection failure

Integration/manual tests:

- Headroom disabled: existing LLM configuration still works.
- Headroom enabled and proxy running: chat and summary calls route to proxy.
- Headroom enabled and proxy stopped: app starts and falls back cleanly.
- Budget mode still blocks calls through `UsageMeter`.
- Development workflow dry-run produces reviewable learned rules and does not
  touch tracked files unless explicitly applied.

## Rollout

Phase 1: add configuration, status detection, routing, docs, and tests. No
auto-start.

Phase 2: add desktop status display and optional Headroom savings stats.

Phase 3: add optional auto-start for local proxy if Windows packaging and process
lifecycle behavior are reliable.

Phase 4: consider deeper Java-side compression only if proxy mode cannot cover a
specific high-value flow.

## Risks

- Proxy compatibility may differ across OpenAI-compatible providers.
- Savings stats may not match SelfAnalyst's provider-reported usage exactly.
- Auto-starting an external Python tool from the desktop app can create packaging
  and lifecycle complexity.
- Failure learning could write overly broad or sensitive rules if not reviewed.
- Routing all LLM calls through a local proxy changes the failure surface, so the
  fallback path must stay simple and visible.
