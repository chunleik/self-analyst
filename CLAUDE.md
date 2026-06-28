# CLAUDE.md — SelfAnalyst Project Conventions

## SDD Document Index

SelfAnalyst follows Specification-Driven Development. Every module and major feature has a formal spec.

### Architecture

| Document | Purpose |
|----------|---------|
| [docs/architecture.md](docs/architecture.md) | System architecture, module boundaries, data flow |

### Module Specs

| Document | Module | Key Prefix |
|----------|--------|------------|
| [docs/specs/core.md](docs/specs/core.md) | App module (CLI, config, memory, agent) | `SPEC-CFG-*`, `SPEC-MEM-*`, `SPEC-AGT-*`, `SPEC-CLI-*` |
| [docs/specs/content.md](docs/specs/content.md) | Content/OCR/UIA module | `SPEC-CTX-*`, `SPEC-OCR-*` |
| [docs/specs/audio.md](docs/specs/audio.md) | Audio capture/transcription module | `SPEC-AU-*` |
| [docs/specs/file.md](docs/specs/file.md) | File watch/summary/semantic-index module | `SPEC-FILE-*` |
| [docs/specs/integration-test.md](docs/specs/integration-test.md) | Integration test module group | `SPEC-ITEST-*` |

### Feature Specs

| Document | Feature | Key Prefix |
|----------|---------|------------|
| [docs/specs/desktop.md](docs/specs/desktop.md) | Tauri desktop shell | `SPEC-DSK-*` |
| [docs/specs/desktop-chat-tab.md](docs/specs/desktop-chat-tab.md) | Chat tab in desktop UI | `SPEC-CHAT-TAB-*` |
| [docs/specs/llm-wiki.md](docs/specs/llm-wiki.md) | LLM Wiki multi-level summaries and semantic index | `SPEC-WIKI-*`, `SPEC-WIKI-SEM-*`, `SPEC-WIKI-EMB-*` |
| [docs/specs/behavior-advice.md](docs/specs/behavior-advice.md) | Behavior-based advice/encouragement display card | `SPEC-ADV-*` |
| [docs/specs/web-search.md](docs/specs/web-search.md) | Agent web search via MCP (Parallel Search) | `SPEC-WS-*` |
| [docs/specs/accessibility-sidecar.md](docs/specs/accessibility-sidecar.md) | UIA→常驻 Rust 边车 + OS 中性无障碍树协议（取代 PowerShell one-shot，预留 macOS） | `SPEC-AXS-*` |
| [docs/specs/llm-budget.md](docs/specs/llm-budget.md) | LLM token 用量限制与每日预算（max_tokens、可配 maxIters、计量 + off/warn/block 预算） | `SPEC-BUDGET-*` |
| [docs/specs/desktop-config-editor.md](docs/specs/desktop-config-editor.md) | 桌面端「配置」改为纯文本编辑器：直接编辑 `config.properties` 原始文本（raw GET/PUT）+ 最近 10 个版本历史（自动命名、查看/切换） | `SPEC-CFGUI-*`、`SPEC-CFGUI-VER-*` |
| [docs/specs/chat-session-store.md](docs/specs/chat-session-store.md) | 桌面端会话改为后端持久化：`{memoryDir}/chat-sessions/` 按会话分片 + 索引 + REST CRUD（取代 localStorage，不迁移旧数据，会话数不设上限，按会话摘要搜索） | `SPEC-CSP-*` |
| [docs/specs/i18n.md](docs/specs/i18n.md) | 桌面端中/英双语国际化：后端全部 LLM 提示词 + 桌面 UI 全部界面文案按有效语言切换；`app.language=zh\|en\|auto`（默认 auto，读系统 Locale，可覆盖），并修复写死中文字面量泄漏 | `SPEC-I18N-*` |

### Archive

| Document | Status |
|----------|--------|
| [docs/archive/design-proposals/2026-06-05-desktop-agent-dashboard-design.md](docs/archive/design-proposals/2026-06-05-desktop-agent-dashboard-design.md) | Superseded by desktop-chat-tab.md and desktop.md |

## Reading the Specs

- Full conventions (structure, ID scheme, spec-vs-plan boundary) live in [docs/specs/README.md](docs/specs/README.md).
- Each spec has numbered requirements (e.g., `SPEC-DSK-TRAY-001`). These IDs  appear in the traceability matrix at the end of each spec and in commit messages.
- The traceability matrix maps spec IDs to source files and verification method.
- Specs use `docs/specs/desktop-chat-tab.md` as the canonical format template for feature specs.

## SDD 命令流（斜杠命令）

`.claude/commands/` 提供一套规格驱动开发的斜杠命令，对齐 spec-kit 的四阶段，
但完全使用本项目的路径与 ID 规约（详见 `.claude/commands/README.md`）：

| 命令 | 作用 | 产物 |
|------|------|------|
| `/sdd-spec <名称>` | 新建/精修 spec，分配 Key Prefix，登记 SDD 索引 | `docs/specs/<slug>.md` |
| `/sdd-plan <slug>` | 写实现计划（File Map + Task） | `docs/superpowers/plans/<日期>-<slug>.md` |
| `/sdd-tasks <slug>` | 把 Task 细化为带 SPEC ID 的 `- [ ]` Step | 同一 plan 文件 |
| `/sdd-implement <slug> [Task n]` | 按 Step 实现、验证、维护追溯矩阵 | 源码 + 追溯矩阵 |

ID 形如 `SPEC-<域>-<子域>-NNN`（只增不改）；spec 末尾维护三列追溯矩阵
`| 规格 ID | 目标文件/组件 | 验证方式 |`。

## Project Structure

```
self-analyst/
├── self-analyst-aw/         (ActivityWatch engine + web UI)
├── self-analyst-content/    (UIA + OCR content recognition)
├── self-analyst-audio/      (Audio capture + speech-to-text)
├── self-analyst-wiki/       (LLM Wiki summaries + semantic index)
├── self-analyst-file/       (Directory file watch + summary + semantic index)
├── self-analyst-app/        (Main app: CLI, desktop API, desktop UI frontend)
├── self-analyst-desktop/    (Tauri 2.x desktop shell)
├── self-analyst-integration-test/  (Integration verification jars)
├── docs/                    (Architecture + specs)
└── scripts/                 (Build, install, check scripts)
```

## Desktop UI Frontend

The desktop UI is at `self-analyst-app/src/main/resources/desktop-ui/`. It is vanilla ES5 JS split into 10 modules loaded via `<script>` tags in this order:

```
utils.js → state.js → api.js → agent.js → chat.js →
chat-drawer.js → config.js → ui.js → events.js → init.js
```

- No build step, no framework, no bundler
- All modules share the global `state` object (declared in `state.js`)
- The Java backend (`DesktopServer.java`) serves these files from classpath via `/desktop-ui/*`
- Tauri's `frontendDist` in `tauri.conf.json` points directly to this directory (no sync copies)

## Commit Conventions

- Spec-driven commits reference spec IDs when applicable
- Co-Authored-By trailer on all commits
- Commit messages in Chinese or English, focused on the "why"
