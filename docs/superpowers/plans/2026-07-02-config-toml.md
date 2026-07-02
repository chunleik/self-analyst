# Config TOML Migration — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Migrate the user-level config file from Java `.properties` to TOML v1.0 (`{memoryDir}/config.toml`): TOML parse/flatten/normalize pipeline, startup auto-migration with backup + history snapshot, raw-editor validation with line/col errors and type checks, version-history `format` field with legacy view-only handling.

**Architecture:** New core helper `TomlSupport` (parse → flatten dotted keys → normalize to string map; TOML text generation; template) is the single TOML entry point, consumed by `Config.load()`, `UserConfigStore`, and `DesktopConfigController`. New `ConfigMigration` (desktop.store) runs once in `AppSession` before `Config.load()`. Internal dotted-key namespace, whitelist, restart set, and key-level diff are unchanged (they operate on the flattened map). Raw editor keeps verbatim read/write; structured save regenerates TOML.

**Tech Stack:** Java 17+ (records, switch expressions), tomlj (TOML 1.0 parser, position-aware errors), Javalin, Jackson, vanilla ES5 JS, JUnit 5

**Spec:** `docs/specs/config-toml.md` — all SPEC-TOML-* requirements

---

### File Map

| File | Action | Purpose |
|------|--------|---------|
| `self-analyst-app/pom.xml` | Modify | Add `org.tomlj:tomlj:1.1.1` dependency |
| `self-analyst-app/src/main/java/com/selfanalyst/config/TomlSupport.java` | Create | Parse/flatten/normalize, type validation, TOML generation, TOML template |
| `self-analyst-app/src/main/java/com/selfanalyst/config/Config.java` | Modify | Load `config.toml` first; expose `resolveMemoryDir()`; keep properties fallbacks |
| `self-analyst-app/src/main/java/com/selfanalyst/desktop/store/UserConfigStore.java` | Modify | Point at `config.toml`; `loadUser()` via TOML; `save()` regenerates TOML |
| `self-analyst-app/src/main/java/com/selfanalyst/desktop/store/ConfigMigration.java` | Create | One-shot startup migration: properties → TOML + `.bak` + history snapshot |
| `self-analyst-app/src/main/java/com/selfanalyst/desktop/store/ConfigHistoryStore.java` | Modify | `format` field on `ConfigVersion`; format-aware sensitive-value redaction |
| `self-analyst-app/src/main/java/com/selfanalyst/AppSession.java` | Modify | Invoke `ConfigMigration.migrateIfNeeded()` before `Config.load()` |
| `self-analyst-app/src/main/java/com/selfanalyst/desktop/controller/DesktopConfigController.java` | Modify | Raw validation via TomlSupport (syntax + structure + type), TOML template, `format` in history responses |
| `self-analyst-app/src/main/resources/desktop-ui/config.js` | Modify | `parseEditorToml()` for test buttons, `config.toml` labels, history format badge + disabled switch |
| `self-analyst-app/src/main/resources/desktop-ui/styles.css` | Modify | Legacy-format history badge / disabled switch styles |
| `self-analyst-app/src/test/java/com/selfanalyst/config/TomlSupportTest.java` | Create | Flatten/normalize/type/generation unit tests |
| `self-analyst-app/src/test/java/com/selfanalyst/config/ConfigTest.java` | Modify | TOML load priority + UTF-8 tests |
| `self-analyst-app/src/test/java/com/selfanalyst/desktop/store/UserConfigStoreRawTest.java` | Modify | Raw round-trip against `config.toml`; structured save regeneration |
| `self-analyst-app/src/test/java/com/selfanalyst/desktop/store/ConfigMigrationTest.java` | Create | Migration trigger/idempotency/failure tests |
| `self-analyst-app/src/test/java/com/selfanalyst/desktop/store/ConfigHistoryStoreTest.java` | Modify | `format` default + TOML redaction tests |
| `self-analyst-app/src/test/java/com/selfanalyst/desktop/controller/DesktopConfigControllerTest.java` | Modify | TOML validation/template/restart/unknown tests |
| `scripts/check-desktop-config-editor.ps1` | Modify | Static checks updated for TOML editor (parseEditorToml, config.toml labels) |
| `docs/specs/config-toml.md` | Modify | Maintain traceability matrix during implementation |

---

### Task 1: tomlj dependency + `TomlSupport` core component

**Files:**
- Modify: `self-analyst-app/pom.xml`
- Create: `self-analyst-app/src/main/java/com/selfanalyst/config/TomlSupport.java`
- Create: `self-analyst-app/src/test/java/com/selfanalyst/config/TomlSupportTest.java`

Implementation points:

- Add `org.tomlj:tomlj:1.1.1` to `self-analyst-app/pom.xml` (parse-only library, TOML 1.0, `TomlParseError` carries `position().line()/column()` — satisfies SPEC-TOML-DEC-007; no writer needed since raw path is verbatim and structured writes are hand-generated).
- `TomlSupport` API (all static, no state):
  - `LinkedHashMap<String,String> parseAndFlatten(String text)` — `Toml.parse(text)`; if `result.hasErrors()`, throw `TomlValidationException` (new checked or runtime exception carrying `List<String>` of `"第 N 行第 M 列: <message>"` entries). Walk the parse tree depth-first, joining table path + key with `.` (`[aw.collection] window` → `aw.collection.window`; top-level dotted keys land identically). // SPEC-TOML-FMT-002a
  - Duplicate keys need no extra handling — tomlj reports them as parse errors per TOML semantics. // SPEC-TOML-FMT-002b
  - Normalization inside the walk: `String` as-is; `Boolean` → `"true"/"false"`; `Long`/`Double` → decimal string; array of primitives → values normalized then joined with `","`. // SPEC-TOML-FMT-003a/b
  - Reject with `TomlValidationException`: date-time values, arrays containing tables/inline tables, mixed-type arrays (tomlj already errors on heterogeneous arrays in 1.0 mode — verify; add explicit checks where it doesn't). // SPEC-TOML-FMT-003c
  - `List<String> validateTypes(Map<String,String> flat, Map<String,KeyType> declared)` — lenient check per SPEC-TOML-FMT-003d: value already normalized to string; for BOOLEAN accept `true/false` (case-insensitive), INTEGER `Long.parseLong`, FLOAT `Double.parseDouble`, LIST/STRING always pass. Returns violation messages (`"aw.port: 期望整数, 实际 \"abc\""`); empty list = pass. Unknown keys are not type-checked.
  - `enum KeyType { STRING, BOOLEAN, INTEGER, FLOAT, LIST }`.
  - `String generateToml(Map<String,String> flat, Map<String,KeyType> declared)` — regeneration for structured save + migration (SPEC-TOML-DEC-004): group keys by the section order already used in `UserConfigStore.save()` (`llm.`, `aw.`, `wiki.`, `embedding.`, `agent.`, `desktop.`, `websearch.`, `file.`); unmatched keys (e.g. `memory.dir`) emitted as top-level dotted assignments **before** the first `[table]` header (TOML requires this ordering). Header comment `# SelfAnalyst 用户配置（config.toml）`.
    - Value emission: if declared type is BOOLEAN/INTEGER/FLOAT and the string value parses, emit bare (`true`, `5700`, `0.7`); otherwise emit a string — literal `'...'` when the value contains `\` and no `'` (Windows paths stay readable, SPEC-TOML-GOAL-002), else basic `"..."` with standard escapes.
    - Key emission: bare when matching `[A-Za-z0-9_-]+`, else quoted.
  - `String buildTemplate(Map<String,String> defaults, Map<String,KeyType> declared)` — TOML template: `[llm]`/`[aw]`/... table headers, each supported key as a commented line `# api-key = ""` with default value and type hint in a trailing comment; file header includes the Windows-path guidance (`# 路径值请用单引号：'D:\docs'，或使用正斜杠`). // SPEC-TOML-FMT-004a/b
- `TomlSupportTest`: flatten equivalence (table / sub-table / top-level dotted), `'D:\docs'` backslash preserved, boolean/number/array normalization, date-time & mixed-array rejection, type validation lenient cases (`port = "5600"` passes, `port = "abc"` fails), generateToml → parseAndFlatten round-trip equality, template parses as valid TOML when uncommented keys are absent.

Covers: SPEC-TOML-FMT-001a, FMT-002a/b, FMT-003a/b/c/d, FMT-004a/b, DEC-003, DEC-007, TST-001..003, TST-012 (template shape).

- [x] **Step 1: Add tomlj dependency** — `self-analyst-app/pom.xml`: add `org.tomlj:tomlj:1.1.1`; `mvn -pl self-analyst-app compile` still green. // SPEC-TOML-DEC-007
- [x] **Step 2: `TomlValidationException` + `parseAndFlatten`** — create `self-analyst-app/src/main/java/com/selfanalyst/config/TomlSupport.java`: exception carrying `List<String>` messages (`"第 N 行第 M 列: <原因>"`), `parseAndFlatten(String)` with depth-first table walk joining path with `.`; duplicate keys surface as tomlj parse errors (no lenient override). Depends: Step 1. // SPEC-TOML-FMT-002a/b
- [x] **Step 3: Value normalization + unsupported-structure rejection** — inside the Step 2 walk: string as-is, boolean → `"true"/"false"`, integer/float → decimal string, primitive array → comma-joined; reject date-time, arrays of (inline) tables, mixed-type arrays with `TomlValidationException` (verify tomlj 1.0 mode already errors on heterogeneous arrays; add explicit checks where it doesn't). Depends: Step 2. // SPEC-TOML-FMT-003a/b/c, SPEC-TOML-DEC-003
- [x] **Step 4: `KeyType` enum + lenient `validateTypes`** — same file: `enum KeyType { STRING, BOOLEAN, INTEGER, FLOAT, LIST }`; `validateTypes(flat, declared)` returns violation messages (`"aw.port: 期望整数, 实际 \"abc\""`), unknown keys skipped, LIST/STRING always pass. Depends: Step 2. // SPEC-TOML-FMT-003d
- [x] **Step 5: `generateToml` + `buildTemplate`** — same file: section-grouped regeneration (`llm.`/`aw.`/`wiki.`/`embedding.`/`agent.`/`desktop.`/`websearch.`/`file.`; unmatched keys as top-level dotted assignments **before** the first table header), typed bare emission vs literal `'...'` for backslash values vs basic `"..."`; template with table headers, commented keys + default/type hints, and Windows-path guidance comment. Depends: Steps 3–4. // SPEC-TOML-FMT-004a/b, SPEC-TOML-DEC-004, SPEC-TOML-GOAL-002
- [x] **Step 6: `TomlSupportTest`** — create `self-analyst-app/src/test/java/com/selfanalyst/config/TomlSupportTest.java`: flatten equivalence (table/sub-table/top-level dotted, TST-001), `'D:\docs'` backslash preserved (TST-002), boolean/number/array normalization (TST-003), date-time & mixed-array rejection (FMT-003c), lenient type cases (`port = "5600"` pass / `"abc"` fail, FMT-003d), generateToml → parseAndFlatten round-trip equality, template parses as valid TOML (TST-012 shape). Depends: Steps 2–5.
- [ ] **Step 7: Commit** — message references `SPEC-TOML-FMT-001..004, SPEC-TOML-DEC-003/007`.

**Task 1 验证**：`mvn -pl self-analyst-app test -Dtest=TomlSupportTest` 全绿 — 覆盖 spec §11 SPEC-TOML-TST-001/002/003 及 TST-012 的模板形态部分。

---

### Task 2: `Config.load()` reads `config.toml` with new priority

**Files:**
- Modify: `self-analyst-app/src/main/java/com/selfanalyst/config/Config.java`
- Modify: `self-analyst-app/src/test/java/com/selfanalyst/config/ConfigTest.java`

Implementation points:

- Extract the current memory-dir resolution (env `MEMORY_DIR` > classpath `memory.dir` > `~/.self-analyst`) into `public static Path resolveMemoryDir()` so `AppSession` can locate the migration target before `Config.load()` (Task 4 depends on it).
- Rework the overlay block in `load()` (currently legacy-home then `{memoryDir}/config.properties`) to implement SPEC-TOML-MIG-002a priority. Overlay order (lowest applied first, later `putAll` wins):
  1. classpath `application.properties` (unchanged, SPEC-TOML-NON-001)
  2. legacy `~/.self-analyst/config.properties` (unchanged properties semantics)
  3. `{memoryDir}/config.toml` if it exists → `TomlSupport.parseAndFlatten` (UTF-8 read), `putAll` into props; **else** `{memoryDir}/config.properties` (existing UTF-8 properties read) — the un-migrated CLI path.
  - Env vars stay on top via the existing `envOrProp` mechanism.
- A TOML parse failure at runtime load: log warning and skip the overlay (same forgiving posture as the existing `catch (IOException ignored)` blocks) — startup must not crash on a hand-broken file; the editor is the strict gate.
- `ConfigTest`: toml overrides classpath default; toml present + properties residue → properties ignored (SPEC-TOML-TST-009 load half); toml absent + properties present → properties still honored; 中文 value via TOML round-trips (SPEC-TOML-TST-011 load half).

Covers: SPEC-TOML-MIG-002a/b (load side), SPEC-TOML-NON-001.

- [x] **Step 1: Extract `resolveMemoryDir()`** — `self-analyst-app/src/main/java/com/selfanalyst/config/Config.java`: pull the env `MEMORY_DIR` > classpath `memory.dir` > `~/.self-analyst` resolution into `public static Path resolveMemoryDir()`; `load()` behavior unchanged. No dependencies (pure refactor; Task 4 Step 3 depends on this). // prerequisite for SPEC-TOML-MIG-001a timing
- [x] **Step 2: Overlay priority rework** — same file: overlay order classpath `application.properties` → legacy `~/.self-analyst/config.properties` → (`{memoryDir}/config.toml` via `TomlSupport.parseAndFlatten` UTF-8 **if exists, else** `{memoryDir}/config.properties`); env vars stay on top via `envOrProp`. Runtime TOML parse failure → log warning + skip overlay (startup never crashes; editor is the strict gate). Depends: Task 1 Step 3, this Task Step 1. // SPEC-TOML-MIG-002a, SPEC-TOML-DEC-001, SPEC-TOML-NON-001
- [x] **Step 3: `ConfigTest` additions** — `self-analyst-app/src/test/java/com/selfanalyst/config/ConfigTest.java`: toml overrides classpath default; toml present + properties residue → properties ignored (TST-009 load half); toml absent + properties present → properties honored; 中文 value via TOML round-trips (TST-011 load half); broken TOML → load succeeds without overlay. Depends: Step 2.
- [ ] **Step 4: Commit** — message references `SPEC-TOML-MIG-002`.

**Task 2 验证**：`mvn -pl self-analyst-app test -Dtest=ConfigTest` 全绿 — 覆盖 SPEC-TOML-TST-009/011 的加载侧。

---

### Task 3: `UserConfigStore` targets `config.toml`

**Files:**
- Modify: `self-analyst-app/src/main/java/com/selfanalyst/desktop/store/UserConfigStore.java`
- Modify: `self-analyst-app/src/test/java/com/selfanalyst/desktop/store/UserConfigStoreRawTest.java`

Implementation points:

- `filePath` → `memoryDir.resolve("config.toml")`. `readRaw()`/`saveRaw()` stay byte-faithful UTF-8 verbatim (unchanged bodies — they are format-agnostic). // SPEC-TOML-API-001d write side
- `loadUser()` → parse via `TomlSupport.parseAndFlatten` into a `Properties` (keeps the return type so `DesktopConfigController`, `ConfigTools`, diff code compile unchanged); unreadable/invalid file → warn + empty, as today.
- `save(Properties)` → delegate to `TomlSupport.generateToml(flatMap, declaredTypes)` + atomic temp/rename write; delete the `.properties`-specific section writer and `escapePropKey`/`escapePropValue` (superseded by TOML emission rules). Structured saves and `ConfigTools.set()` thereby persist TOML with no signature changes. // SPEC-TOML-API-002a/b, SPEC-TOML-DEC-004
- Declared-types map: `UserConfigStore` gets it from the controller-side single source of truth — move `SUPPORTED_DEFAULTS` (key → default) out of `DesktopConfigController` into a shared `TomlSupport`-adjacent constant (e.g. `SupportedKeys` map of key → `(default, KeyType)`) so template, unknown-key detection, type validation, and generation all read one definition. Controller keeps consuming it (SPEC-TOML-NON-002 — key set itself unchanged; typed: BOOLEAN for `*.enabled`/`aw.collection.*`/`agent.allowAgentTasks`/`agent.cacheSummaries`/`desktop.*`/`embedding.send-encoding-format`, INTEGER for `aw.port`/`agent.summaryRefreshMinutes`/`embedding.dimensions`, FLOAT for `llm.temperature`, STRING otherwise).
- `UserConfigStoreRawTest`: raw round-trip verbatim on `config.toml` (SPEC-TOML-TST-006), `set()`/`save()` output re-parses to the same flat map and is readable by `Config.load()` (SPEC-TOML-TST-014/015 store half), UTF-8 中文 values.

Covers: SPEC-TOML-FMT-001a/b, API-002a/b (persistence side), MIG-002b (store/load consistency).

- [x] **Step 1: Shared `SupportedKeys` extraction** — create the key → `(default, KeyType)` single source of truth next to `TomlSupport` (e.g. `self-analyst-app/src/main/java/com/selfanalyst/config/SupportedKeys.java`), moving `SUPPORTED_DEFAULTS` out of `DesktopConfigController`; controller keeps consuming it; key set itself unchanged. Depends: Task 1 Step 4 (`KeyType`). // SPEC-TOML-NON-002
- [x] **Step 2: Retarget `UserConfigStore` + TOML `loadUser()`** — `self-analyst-app/src/main/java/com/selfanalyst/desktop/store/UserConfigStore.java`: `filePath` → `memoryDir.resolve("config.toml")`; `loadUser()` via `TomlSupport.parseAndFlatten` into a `Properties` (return type unchanged so controller/`ConfigTools`/diff compile as-is); unreadable/invalid → warn + empty; `readRaw()`/`saveRaw()` bodies untouched (verbatim UTF-8). Depends: Task 1 Step 2. // SPEC-TOML-FMT-001a/b, SPEC-TOML-MIG-002b, SPEC-TOML-API-001d (write side)
- [x] **Step 3: `save(Properties)` regenerates TOML** — same file: delegate to `TomlSupport.generateToml(flatMap, SupportedKeys)` + atomic temp/rename write; delete the `.properties` section writer and `escapePropKey`/`escapePropValue`. Structured saves and `ConfigTools.set()` thereby persist TOML with no signature change. Depends: Task 1 Step 5, this Task Steps 1–2. // SPEC-TOML-API-002a/b, SPEC-TOML-DEC-004
- [x] **Step 4: `UserConfigStoreRawTest` updates** — `self-analyst-app/src/test/java/com/selfanalyst/desktop/store/UserConfigStoreRawTest.java`: raw round-trip verbatim on `config.toml` (TST-006); `set()`/`save()` output re-parses to the same flat map and is readable by `Config.load()` (TST-014/015 store half); UTF-8 中文 values. Depends: Steps 2–3.
- [ ] **Step 5: Commit** — message references `SPEC-TOML-API-002, SPEC-TOML-FMT-001, SPEC-TOML-NON-002`.

**Task 3 验证**：`mvn -pl self-analyst-app test -Dtest=UserConfigStoreRawTest` 全绿 — 覆盖 SPEC-TOML-TST-006 及 TST-014/015 的存储侧。

---

### Task 4: Startup migration `ConfigMigration`

**Files:**
- Create: `self-analyst-app/src/main/java/com/selfanalyst/desktop/store/ConfigMigration.java`
- Create: `self-analyst-app/src/test/java/com/selfanalyst/desktop/store/ConfigMigrationTest.java`
- Modify: `self-analyst-app/src/main/java/com/selfanalyst/AppSession.java`

Implementation points:

- `public static void migrateIfNeeded(Path memoryDir)`:
  1. Guard: `config.toml` exists → return (idempotent; stale `config.properties` ignored). `config.properties` absent → return. // SPEC-TOML-MIG-001a
  2. Parse old file with `Properties.load` over a UTF-8 `Reader` (matches SPEC-CFGUI-DEC-005 write encoding).
  3. `TomlSupport.generateToml(...)` → atomic write `config.toml` (temp + rename).
  4. Rename `config.properties` → `config.properties.bak` (`REPLACE_EXISTING`). // SPEC-TOML-MIG-001b/c
  5. History snapshot via `new ConfigHistoryStore(memoryDir).add(...)` with deterministic summary `"从 config.properties 自动迁移"`, `format="toml"` (Task 6 adds the field; this task passes it). // SPEC-TOML-MIG-001d
  - Any failure in 2–4: log error, delete partial temp, leave both files untouched, return normally (startup must not break; loader falls back to properties per Task 2 priority). Snapshot failure (step 5) is caught separately — migration still counts as successful. // SPEC-TOML-MIG-001c
- `AppSession` constructor: `ConfigMigration.migrateIfNeeded(Config.resolveMemoryDir())` as the **first** statement, before `Config.load()` — this is the single backend entry (`AppSession` is constructed by both CLI and desktop paths), so "首次读取之前" holds; a standalone `Config.load()` without `AppSession` is exactly the un-migrated path SPEC-TOML-MIG-002a-3 covers.
- `ConfigMigrationTest` (temp dirs): converts + renames + snapshot (TST-008); no-op when toml exists (TST-009); corrupted source → no toml, no rename, no throw (TST-010); 中文/backslash values survive conversion (TST-002/011 migration half); second invocation no-op.

Covers: SPEC-TOML-MIG-001a..d, DEC-005, GOAL-003.

- [x] **Step 1: `ConfigMigration` core with failure isolation** — create `self-analyst-app/src/main/java/com/selfanalyst/desktop/store/ConfigMigration.java`: `migrateIfNeeded(Path memoryDir)` — guard (toml exists → return; properties absent → return), UTF-8 `Properties.load`, `TomlSupport.generateToml` → atomic write, rename to `config.properties.bak` (`REPLACE_EXISTING`); any failure in parse/write/rename → log error, delete partial temp, leave both files untouched, return normally. Depends: Task 1 Step 5. // SPEC-TOML-MIG-001a/b/c, SPEC-TOML-DEC-005, SPEC-TOML-GOAL-003
- [x] **Step 2: Migration history snapshot** — same file: on success, `new ConfigHistoryStore(memoryDir).add(...)` with summary `"从 config.properties 自动迁移"` and `format="toml"`; snapshot failure caught separately (migration still counts as successful). Depends: Step 1, **Task 6 Step 1**（`add(...)` 的 `format` 参数）— 若按 Task 顺序实现，可先做 Task 6 Step 1 或本步暂以现有 `add(...)` 签名落地、Task 6 落地后补传 `format`。 // SPEC-TOML-MIG-001d
- [x] **Step 3: `AppSession` wiring** — `self-analyst-app/src/main/java/com/selfanalyst/AppSession.java`: `ConfigMigration.migrateIfNeeded(Config.resolveMemoryDir())` as the **first** constructor statement, before `Config.load()` (single backend entry for both CLI and desktop paths). Depends: Task 2 Step 1, this Task Step 1. // SPEC-TOML-MIG-001a（"首次读取之前"时机）
- [x] **Step 4: `ConfigMigrationTest`** — create `self-analyst-app/src/test/java/com/selfanalyst/desktop/store/ConfigMigrationTest.java` (temp dirs): converts + renames + snapshot (TST-008); no-op when toml exists (TST-009); corrupted source → no toml, no rename, no throw (TST-010); 中文/backslash values survive (TST-002/011 migration half); second invocation no-op. Depends: Steps 1–3.
- [ ] **Step 5: Commit** — message references `SPEC-TOML-MIG-001`.

**Task 4 验证**：`mvn -pl self-analyst-app test -Dtest=ConfigMigrationTest` 全绿 — 覆盖 SPEC-TOML-TST-008/009/010 及 TST-002/011 的迁移侧。

---

### Task 5: `DesktopConfigController` raw path → TOML validation + template

**Files:**
- Modify: `self-analyst-app/src/main/java/com/selfanalyst/desktop/controller/DesktopConfigController.java`
- Modify: `self-analyst-app/src/test/java/com/selfanalyst/desktop/controller/DesktopConfigControllerTest.java`

Implementation points:

- Replace `parseProperties(text)` inside `applyRawSave` with `TomlSupport.parseAndFlatten` + `TomlSupport.validateTypes` against `SupportedKeys`; wrap flat maps in `Properties` so `computeRestartRequired`/`computeUnknownKeys`/`computeDiffSummary` keep working on dotted keys unchanged. // SPEC-TOML-API-001d, DEC-002
- `putRawConfig` error handling: catch `TomlValidationException` → 400 with the line/col messages joined (`配置文本无效: 第 3 行第 5 列: ...`); type violations → 400 listing keys + expected types. IO failures stay 500. Disk untouched on any 400 (validation precedes `saveRaw`, as today). // SPEC-TOML-API-001b/c, GOAL-005
- `buildTemplate()` → delegate to `TomlSupport.buildTemplate(SupportedKeys)`. `buildRawResponse()` unchanged shape; `path` now naturally reports `config.toml`. // SPEC-TOML-API-001a, FMT-004
- Structured `getConfig`/`putConfig`, `test-llm`/`test-embedding`, and the key-name-only LLM summary refinement need **no logic change** (they consume `userStore.load()`/`save()` and dotted keys) — verify via tests only. // SPEC-TOML-API-002a/c, VER-003
- `DesktopConfigControllerTest`: invalid TOML → 400 + line/col + disk unchanged (TST-004); type violation → 400 with key list (TST-005); valid save → verbatim round-trip (TST-006); restart/unknown semantics preserved (TST-007); missing file → TOML template with table headers + path guidance (TST-012); structured PUT regenerates parseable TOML (TST-015).

Covers: SPEC-TOML-API-001a..d, API-002a/c, FMT-003c/d (endpoint enforcement), GOAL-005.

- [x] **Step 1: Swap raw validation pipeline** — `self-analyst-app/src/main/java/com/selfanalyst/desktop/controller/DesktopConfigController.java`: replace `parseProperties(text)` in `applyRawSave` with `TomlSupport.parseAndFlatten` + `TomlSupport.validateTypes(SupportedKeys)`; wrap flat maps in `Properties` so `computeRestartRequired`/`computeUnknownKeys`/`computeDiffSummary` operate unchanged on dotted keys. Depends: Task 1 Steps 2/4, Task 3 Step 1. // SPEC-TOML-API-001d, SPEC-TOML-DEC-002
- [x] **Step 2: Error mapping in `putRawConfig`** — same file: `TomlValidationException` → 400 with joined line/col messages (`配置文本无效: 第 3 行第 5 列: ...`); type violations → 400 listing keys + expected types; IO stays 500; validation precedes `saveRaw` so disk untouched on any 400. Depends: Step 1. // SPEC-TOML-API-001b/c, SPEC-TOML-FMT-003c/d（端点强制）, SPEC-TOML-GOAL-005
- [x] **Step 3: TOML template delegation** — same file: `buildTemplate()` → `TomlSupport.buildTemplate(SupportedKeys)`; `buildRawResponse()` shape unchanged, `path` now reports `config.toml`. Depends: Task 1 Step 5, Task 3 Step 1. // SPEC-TOML-API-001a, SPEC-TOML-FMT-004
- [x] **Step 4: Verify structured endpoints untouched** — confirm `getConfig`/`putConfig`, `test-llm`/`test-embedding`, and the key-name-only LLM summary refinement need no logic change (they consume `userStore.load()`/`save()` and dotted keys) — assert via tests only, no edits. Depends: Task 3 Steps 2–3. // SPEC-TOML-API-002a/c
- [x] **Step 5: `DesktopConfigControllerTest` updates** — `self-analyst-app/src/test/java/com/selfanalyst/desktop/controller/DesktopConfigControllerTest.java`: invalid TOML → 400 + line/col + disk unchanged (TST-004); type violation → 400 with key list (TST-005); valid save → verbatim round-trip (TST-006); restart/unknown semantics preserved (TST-007); missing file → TOML template with table headers + path guidance (TST-012); structured PUT regenerates parseable TOML (TST-015). Depends: Steps 1–4.
- [ ] **Step 6: Commit** — message references `SPEC-TOML-API-001..002, SPEC-TOML-GOAL-005`.

**Task 5 验证**：`mvn -pl self-analyst-app test -Dtest=DesktopConfigControllerTest` 全绿 — 覆盖 SPEC-TOML-TST-004/005/006/007/012/015。

---

### Task 6: Version history `format` field + redaction adaptation

**Files:**
- Modify: `self-analyst-app/src/main/java/com/selfanalyst/desktop/store/ConfigHistoryStore.java`
- Modify: `self-analyst-app/src/main/java/com/selfanalyst/desktop/controller/DesktopConfigController.java`
- Modify: `self-analyst-app/src/test/java/com/selfanalyst/desktop/store/ConfigHistoryStoreTest.java`

Implementation points:

- `ConfigVersion` record gains `String format`; Jackson deserialization of legacy manifests yields `null` → normalize to `"properties"` in `readAll()` (SPEC-TOML-VER-001). `add(...)` takes format; all new saves pass `"toml"`.
- Sensitive-value redaction becomes format-aware: for `format="toml"` lines, redact to `key = ""` (keeps the snapshot valid TOML so a 切换-then-save of a redacted version fails on the *missing key value* semantics rather than a syntax bomb — and legacy `key=` properties behavior is preserved for old snapshots). Key extraction: strip optional surrounding quotes before the sensitive-suffix check so `"llm.api-key"` and `api-key` both match.
- `getConfigHistory`/`getConfigVersion` responses include `format`. // SPEC-TOML-VER-001
- Diff summary across formats needs no change: `recordVersion` diffs the flattened old vs new maps (both sides already dotted keys). // SPEC-TOML-VER-003
- `ConfigHistoryStoreTest`: legacy manifest (no `format`) reads as `"properties"`; new adds carry `"toml"`; TOML redaction produces `api-key = ""`; retention/pruning untouched (regression).

Covers: SPEC-TOML-VER-001, VER-003, TST-013 (backend half).

- [x] **Step 1: `format` field + normalization + add-path** — `self-analyst-app/src/main/java/com/selfanalyst/desktop/store/ConfigHistoryStore.java`: `ConfigVersion` record gains `String format`; legacy manifests deserialize to `null` → normalize to `"properties"` in `readAll()`; `add(...)` takes format, all new saves pass `"toml"`（解锁 Task 4 Step 2 的迁移快照参数）. No dependencies. // SPEC-TOML-VER-001
- [x] **Step 2: Format-aware redaction** — same file: `format="toml"` lines redact to `key = ""` (snapshot stays valid TOML); legacy `key=` behavior preserved for properties snapshots; strip surrounding quotes before the sensitive-suffix check so `"llm.api-key"` and `api-key` both match. Depends: Step 1. // SPEC-TOML-VER-001, SPEC-TOML-DEC-006
- [x] **Step 3: History responses carry `format`** — `self-analyst-app/src/main/java/com/selfanalyst/desktop/controller/DesktopConfigController.java`: include `format` in `getConfigHistory`/`getConfigVersion` responses; confirm key-level diff needs no change (`recordVersion` diffs flattened dotted-key maps on both sides). Depends: Step 1. // SPEC-TOML-VER-001/003
- [x] **Step 4: `ConfigHistoryStoreTest` updates** — `self-analyst-app/src/test/java/com/selfanalyst/desktop/store/ConfigHistoryStoreTest.java`: legacy manifest (no `format`) reads as `"properties"`; new adds carry `"toml"`; TOML redaction produces `api-key = ""`; retention/pruning regression untouched. Depends: Steps 1–3.
- [ ] **Step 5: Commit** — message references `SPEC-TOML-VER-001/003`.

**Task 6 验证**：`mvn -pl self-analyst-app test -Dtest=ConfigHistoryStoreTest` 全绿 — 覆盖 SPEC-TOML-TST-013 的后端半侧（UI 半侧在 Task 7）。

---

### Task 7: Desktop UI adaptation

**Files:**
- Modify: `self-analyst-app/src/main/resources/desktop-ui/config.js`
- Modify: `self-analyst-app/src/main/resources/desktop-ui/styles.css`

Implementation points:

- Replace `parseEditorProps()` with `parseEditorToml()`: line-based mini-parser sufficient for the test buttons — track current `[table]` header (strip whitespace/quotes), skip blank/`#` lines, split `key = value` on first `=`, strip surrounding `"..."`/`'...'` from values, compose `table.key` dotted names; top-level dotted keys pass through. `readLlmConfigFromEditor`/`readEmbeddingConfigFromEditor` keep their dotted lookups (`llm.base-url` etc.) so both table and dotted forms resolve. // SPEC-TOML-UI-003, TST-016
- Labels/strings mentioning `config.properties` in the modal (title/hint text in `config.js` render functions) → `config.toml`; the `path` shown comes from the API and updates automatically. // SPEC-TOML-UI-001
- Save-failure display already prints the backend `error` string (now containing line/col + violating keys) — verify no truncation; keep dirty state on failure. // SPEC-TOML-UI-002
- History list rendering: read `format` from the version items; when it differs from `"toml"`, render badge `旧格式（properties），仅可查看` and disable/hide the 「切换」 button (keep 「查看」). Add a small `.config-version-legacy` badge style + disabled-button styling in `styles.css`. // SPEC-TOML-VER-002, TST-013 (UI half)

Covers: SPEC-TOML-UI-001..003, VER-002.

- [x] **Step 1: `parseEditorToml()` mini-parser** — `self-analyst-app/src/main/resources/desktop-ui/config.js`: replace `parseEditorProps()` with a line-based parser (track `[table]` header, skip blank/`#`, split on first `=`, strip `"..."`/`'...'`, compose dotted names); `readLlmConfigFromEditor`/`readEmbeddingConfigFromEditor` keep dotted lookups so both table and top-level dotted forms resolve; keep the existing fallback when keys don't parse. Depends: none (backend-independent). // SPEC-TOML-UI-003
- [x] **Step 2: Label/hint updates** — same file: modal title/hint strings mentioning `config.properties` → `config.toml` (the `path` shown comes from the API and updates automatically once Task 5 lands). // SPEC-TOML-UI-001
- [x] **Step 3: Save-failure display check** （已确认：`api.saveRawConfig` 抛出 `payload.error`，`config.js` 完整渲染 `err.message`，无截断，失败保留脏态；无需改动） — same file: verify the existing 400-error rendering shows the full backend `error` string (now line/col + violating keys) without truncation and keeps dirty state; adjust only if truncation exists. Depends: Task 5 Step 2 for real payloads. // SPEC-TOML-UI-002
- [x] **Step 4: History legacy badge + disabled switch** — `config.js`: read `format` from version items; when ≠ `"toml"` render `旧格式（properties），仅可查看` badge and disable/hide 「切换」 (keep 「查看」); `self-analyst-app/src/main/resources/desktop-ui/styles.css`: add `.config-version-legacy` badge + disabled-button styles. Depends: Task 6 Step 3 (`format` in responses). // SPEC-TOML-VER-002
- [ ] **Step 5: Manual smoke via desktop UI** — start backend + desktop shell: open config modal (TOML text + `config.toml` path), delete file → template shows table headers + path guidance, save invalid TOML → line/col error shown + dirty kept, save valid → round-trip, 「测试 LLM 连接」 with keys in `[llm]` table (TST-016), legacy properties version entry shows badge + disabled switch (TST-013 UI half). Depends: Steps 1–4, Tasks 5–6.
- [ ] **Step 6: Commit** — message references `SPEC-TOML-UI-001..003, SPEC-TOML-VER-002`.

**Task 7 验证**：Step 5 的手动验收清单 — 对应 spec §11 SPEC-TOML-TST-013（UI 半侧）与 TST-016（手动/UI）。

---

### Task 8: Static checks, full verification, traceability

**Files:**
- Modify: `scripts/check-desktop-config-editor.ps1`
- Modify: `docs/specs/config-toml.md` (traceability matrix status)

Implementation points:

- Update `check-desktop-config-editor.ps1`: assert `parseEditorToml` exists (and `parseEditorProps` gone), `config.toml` label present, legacy-format badge markup/classes present.
- Full verification:
  - `mvn test -pl self-analyst-app` then full `mvn test`.
  - Run all `scripts/check-desktop-*.ps1`.
  - Integration `configRoundTrip` (structured endpoints) must still pass against the TOML store (SPEC-CFGUI-API-003a compatibility) — run `self-analyst-integration-test`.
  - Manual upgrade rehearsal: seed a `{memoryDir}/config.properties` with 中文 + `D:\...` path values, start backend, confirm `config.toml` + `.bak` + migration snapshot, editor loads TOML, runtime values identical.
- Update the spec's traceability matrix rows to point at the final files; reference SPEC IDs in commit messages throughout.

Covers: SPEC-TOML-TST-001..016 (execution), GOAL-001..006 (acceptance).

- [x] **Step 1: Static-check script update** — `scripts/check-desktop-config-editor.ps1`: assert `parseEditorToml` exists (and `parseEditorProps` gone), `config.toml` label present, legacy-format badge markup/classes present; run it green. Depends: Task 7 Steps 1–4.
- [x] **Step 2: Full test sweep** （`mvn test` 全 reactor BUILD SUCCESS，app 模块 81 测试全绿；4 个 `check-desktop-*.ps1` 全过） — `mvn test -pl self-analyst-app`, then full `mvn test`; run all `scripts/check-desktop-*.ps1`. Depends: Tasks 1–7. // SPEC-TOML-TST-001..015 执行
- [x] **Step 3: Integration verification** （`AppVerification` jar：`config GET/PUT round trip` PASS，4 passed / 0 failed，结构化端点对 TOML store 兼容） — run `self-analyst-integration-test`: `configRoundTrip` (structured endpoints) must pass against the TOML store (`SPEC-CFGUI-API-003a` compatibility). Depends: Step 2. // SPEC-TOML-API-002a
- [~] **Step 4: Manual upgrade rehearsal** — seed `{memoryDir}/config.properties` with 中文 + `D:\...` path values, start backend, confirm `config.toml` + `.bak` + migration snapshot created, editor loads TOML, runtime values identical. Depends: Step 2. // SPEC-TOML-GOAL-002/003, SPEC-TOML-TST-008/011 端到端
  - 自动化等价物已覆盖：`ConfigMigrationTest.chineseAndBackslashValuesSurviveConversion`（迁移生成 `config.toml`+`.bak`+快照，中文/反斜杠无损）与 `ConfigTest.chineseTomlValueRoundTripsThroughOverlay`（overlay 读回一致）。**未执行**的部分：在本环境启动完整桌面后端做人工端到端演练（需嵌入式 AW/端口/桌面壳）。
- [x] **Step 5: Non-goal review** （已核对：NON-001 `Config.java` 仍以 properties 读 classpath/legacy；NON-002 `SupportedKeys` 键集与旧 `SUPPORTED_DEFAULTS` 逐项一致（27 键）；NON-003 仅新增行解析器、无高亮/补全；NON-004 `generateToml` 重生成不含用户注释；NON-005 未新增反向工具、保留 `.bak`） — code-review pass confirming scope guards: classpath/legacy properties untouched (NON-001), key set unchanged (NON-002), no highlight/completion added (NON-003), structured writes drop comments as before (NON-004), no reverse-rollback tool (NON-005). // SPEC-TOML-NON-001..005
- [x] **Step 6: Traceability matrix sync**（`docs/specs/config-toml.md` 追溯矩阵已指向 `TomlSupport.java`/`SupportedKeys.java`/`ConfigMigration.java`/`TomlValidationException.java` 等最终文件并标注验证方式）。**Final commit 未执行**（用户未要求提交；各 Task 的 commit step 亦保留未勾选）。

**Task 8 验证**：Step 2–4 全绿即 SPEC-TOML-GOAL-001..006 验收完成；spec §11 十六条测试规格全部执行过（单测 + 集成 + 手动）。

---

### Traceability Matrix (Spec ID → Task)

| Spec ID | Task(s) |
|---------|---------|
| SPEC-TOML-DEC-001..007 | Tasks 1–6 (decisions embodied; DEC-001 Task 2, DEC-002/003 Tasks 1/3/5, DEC-004 Tasks 3/5, DEC-005 Task 4, DEC-006 Tasks 6/7, DEC-007 Task 1) |
| SPEC-TOML-GOAL-001..006 | All tasks; acceptance in Task 8 |
| SPEC-TOML-FMT-001..004 | Task 1 (core), Tasks 3/5 (consumption) |
| SPEC-TOML-MIG-001a..d | Task 4 |
| SPEC-TOML-MIG-002a/b | Task 2 (load), Task 3 (store consistency) |
| SPEC-TOML-API-001a..d | Task 5 |
| SPEC-TOML-API-002a..c | Tasks 3, 5 |
| SPEC-TOML-UI-001..003 | Task 7 |
| SPEC-TOML-VER-001..003 | Task 6 (backend), Task 7 (UI) |
| SPEC-TOML-NON-001..005 | Tasks 1–3 (scope guards); review in Task 8 |
| SPEC-TOML-TST-001..016 | Tests distributed Tasks 1–7; executed Task 8 |
