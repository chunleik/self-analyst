# SelfAnalyst Documentation

## Architecture

- [architecture.md](architecture.md) — System architecture, module boundaries, config overview, data flow

## Specifications (SDD)

- [specs/README.md](specs/README.md) — SDD conventions: spec structure, ID scheme, traceability, spec-vs-plan boundary

### Modules

- [specs/core.md](specs/core.md) — App module: configuration, memory system, ReActAgent, server startup
- [specs/content.md](specs/content.md) — Content module: UIA tree traversal, OCR (PaddleOCR/Tesseract), screen capture
- [specs/audio.md](specs/audio.md) — Audio module: Java Sound capture, VAD, whisper.cpp transcription
- [specs/file.md](specs/file.md) — File watch module: directory monitoring, content extraction (PDF/Office/image OCR), LLM summary, semantic index
- [specs/integration-test.md](specs/integration-test.md) — Integration test module group: per-module end-to-end verification jars

### Features

- [specs/desktop.md](specs/desktop.md) — Tauri desktop shell: window, tray, single-instance, Java backend lifecycle
- [specs/desktop-chat-tab.md](specs/desktop-chat-tab.md) — Desktop chat tab: session management, messaging, context, task suggestions
- [specs/llm-wiki.md](specs/llm-wiki.md) — LLM Wiki: multi-level task summaries and local semantic index
- [specs/behavior-advice.md](specs/behavior-advice.md) — Behavior-based advice/encouragement display card on the desktop Agent tab
- [specs/web-search.md](specs/web-search.md) — Agent web search via MCP (Parallel Search)
- [specs/accessibility-sidecar.md](specs/accessibility-sidecar.md) — Accessibility sidecar: replace one-shot PowerShell UIA with a long-lived Rust sidecar over an OS-neutral protocol (macOS-ready)
- [specs/long-term-memory.md](specs/long-term-memory.md) — Long-term memory: extract durable memories from chat, confirm sensitive/inferred items, and manage memory from sessions

## Archive

- [archive/design-proposals/](archive/design-proposals/) — Historical design proposals (superseded by formal specs)
