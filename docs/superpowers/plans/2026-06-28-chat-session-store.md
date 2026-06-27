# Chat Session Backend Persistence — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Refine coarse steps with `/sdd-tasks chat-session-store` before implementing.

**Goal:** Move the desktop `会话` tab's session/message data out of WebView `localStorage` into backend **per-session sharded files** under `{memoryDir}/chat-sessions/`, exposed through REST CRUD, with no session-count cap and a per-session searchable summary.

**Architecture:** New `ChatSessionStore` owns sharded persistence (`index.json` projection + one `<sessionId>.json` per session, atomic temp-file + rename, per-shard isolation, server-enforced truncation invariants, index rebuild from shards). New `ChatSummaryService` produces a one-line session summary via LLM with a deterministic fallback. New `DesktopChatSessionController` exposes the REST contract and triggers async/best-effort summary regeneration through a debounced executor. `DesktopServer` constructs the store/service/controller and registers `/desktop/chat/sessions*` + `/desktop/chat/active-session` routes on the shared Javalin instance. The vanilla ES5 frontend (`api.js` / `chat.js` / `state.js` / `init.js` / `events.js`) drops `localStorage` as the source of truth, loads the index on init, lazy-loads message bodies on activation, and routes every write through the new endpoints.

**Tech Stack:** Java 17+ (records, `Files.move` ATOMIC_MOVE), Jackson (`JavaTimeModule`, `@JsonIgnoreProperties(ignoreUnknown=true)`), Javalin, JUnit 5, vanilla ES5 JS (no build step), PowerShell static-check script.

**Spec:** `docs/specs/chat-session-store.md` — all `SPEC-CSP-*` requirements.

---

### File Map

| File | Action | Purpose |
|------|--------|---------|
| `self-analyst-app/src/main/java/com/selfanalyst/desktop/store/ChatSessionStore.java` | Create | Sharded persistence: models (`Session`/`Message`/`SessionMeta`/`Index`), atomic write, CRUD, truncation invariants, index rebuild, summary write |
| `self-analyst-app/src/main/java/com/selfanalyst/desktop/service/ChatSummaryService.java` | Create | One-line session summary: LLM via `SummaryTextClient` + deterministic fallback (user-message snippets) |
| `self-analyst-app/src/main/java/com/selfanalyst/desktop/controller/DesktopChatSessionController.java` | Create | REST handlers for §7 contract + debounced async summary regeneration |
| `self-analyst-app/src/main/java/com/selfanalyst/desktop/DesktopServer.java` | Modify | Construct store/service/controller; register routes |
| `self-analyst-app/src/main/resources/desktop-ui/api.js` | Modify | Add 8 session/message REST client methods (SPEC-CSP-FE-001) |
| `self-analyst-app/src/main/resources/desktop-ui/chat.js` | Modify | Replace localStorage layer with REST: load index, lazy-load bodies, create/delete/rename/active, summary-based search |
| `self-analyst-app/src/main/resources/desktop-ui/state.js` | Modify | Keep in-memory caches; drop `CHAT_STORAGE_KEY` as data source (retain const for one-time cleanup) |
| `self-analyst-app/src/main/resources/desktop-ui/init.js` | Modify | Async session load before first chat render; delete legacy `localStorage` key |
| `self-analyst-app/src/main/resources/desktop-ui/events.js` | Modify | New-session button + send/retry become promise-based against REST |
| `self-analyst-app/src/test/java/com/selfanalyst/desktop/store/ChatSessionStoreTest.java` | Create | Unit tests for CRUD, invariants, isolation, atomicity, index rebuild |
| `self-analyst-app/src/test/java/com/selfanalyst/desktop/service/ChatSummaryServiceTest.java` | Create | Unit tests for deterministic fallback + LLM happy path |
| `scripts/check-desktop-chat-session-store.ps1` | Create | Static checks: api.js methods, no localStorage source-of-truth in chat.js, legacy-key deletion, summary-based search |

---

### Task 1: `ChatSessionStore` — sharded persistence, CRUD, invariants, index rebuild

**Files:**
- Create: `self-analyst-app/src/main/java/com/selfanalyst/desktop/store/ChatSessionStore.java`

Implements `SPEC-CSP-MODEL-001..005`, `SPEC-CSP-API-009`, `SPEC-CSP-API-010`, and the persistence half of `API-001..008` / `API-011` (summary write-back).

**Design decisions / how to implement:**

- **Layout (SPEC-CSP-DEC-008):** constructor takes `Path memoryDir`; resolve `dir = memoryDir.resolve("chat-sessions")`. `index.json` at `dir/index.json`; each shard at `dir/<sessionId>.json`. Mirror `TaskStore`'s static `ObjectMapper` config: `registerModule(new JavaTimeModule())`, `enable(INDENT_OUTPUT)`, `disable(WRITE_DATES_AS_TIMESTAMPS)`.
- **Models (all `@JsonIgnoreProperties(ignoreUnknown=true)` — SPEC-CSP-MODEL-004):**
  - `public static class Session { String id, title, createdAt, updatedAt, source, contextLabel, summary; Object contextSnapshot; List<Message> messages; }` — use `Instant` with `@JsonFormat(shape=STRING)` for `createdAt`/`updatedAt` (matches `TaskStore.Task`), or ISO strings; pick `Instant` for consistency. `contextSnapshot`/`suggestedTasks` typed as `Object`/`List<Object>` so they round-trip opaquely (SPEC-CSP-MODEL-003).
  - `public static class Message { String id, role, content, error, status; Instant createdAt; Object contextSnapshot; List<Object> suggestedTasks; }`.
  - `public static class SessionMeta { String id, title, source, contextLabel, summary, lastMessagePreview; Instant createdAt, updatedAt; int messageCount; }` — projection only, never carries `messages`.
  - `private static class Index { String activeSessionId; List<SessionMeta> sessions = new ArrayList<>(); }` (private; serialized as `index.json`).
- **id generation (SPEC-CSP-MODEL-001):** `UUID.randomUUID().toString().replace("-", "")` (filesystem-safe, no separators) — used as the shard filename. Message ids via the same generator (or `substring(0,12)`). Client-supplied `id` is always overwritten.
- **Server-owned fields (SPEC-CSP-MODEL-002):** server sets `createdAt`/`updatedAt`/`summary`; ignore client values for these.
- **Atomic write (SPEC-CSP-API-010a):** private `writeJson(Path target, Object value)` = `Files.createDirectories(dir)` → write `target + ".tmp"` → `Files.move(tmp, target, REPLACE_EXISTING, ATOMIC_MOVE)` (copy `TaskStore.save`). On `IOException` throw `RuntimeException` so the controller maps it to HTTP 500 with the on-disk original untouched.
- **Per-shard isolation (SPEC-CSP-API-010b):** any single-session write touches only `<id>.json` + `index.json`. Never rewrite sibling shards. Index update mutates only the one matching `SessionMeta` row (+ reorders/rewrites the list).
- **Truncation invariants, server-enforced (SPEC-CSP-API-009):**
  - `API-009a` — no session-count cap anywhere (no pruning by count).
  - `API-009b` — after appending, if `messages.size() > 200`, drop oldest down to 200 (`subList`/`removeRange`).
  - `API-009c` — before persisting any message, if `content.length() > 20000`, truncate to 20000 and append `"..."`. Apply on both append and update.
- **Index as derived projection + rebuild (SPEC-CSP-MODEL-005 / API-010c):**
  - `loadIndex()` reads `index.json`; on missing → empty `Index`; on parse failure → log warning and **rebuild from shards** (do not delete the corrupt file silently).
  - `rebuildIndex()` — scan `dir` for `*.json` except `index.json`, parse each `Session` (skip + warn on per-shard parse failure, never throw process-fatal), build `SessionMeta` rows, preserve `activeSessionId` if still resolvable else `null`, persist rebuilt `index.json`, return it.
  - `toMeta(Session)` derives `lastMessagePreview` (last message `content`, trimmed to ~80 chars), `messageCount`, and copies `summary`.
- **Public API (called by controller):**
  - `Index listIndex()` — returns index with `sessions` sorted by `updatedAt` **descending** (SPEC-CSP-API-001). Empty dir → `activeSessionId=null, sessions=[]`.
  - `Session getSession(String id)` — read shard or `null` if absent (SPEC-CSP-API-002).
  - `Session create(CreateRequest req)` — assign id/timestamps, default `title="新会话"`, `source="manual"`; assign ids/`createdAt` to any `initialMessages` (apply 009c, then 009b); write shard; set `activeSessionId=id`; upsert index row; **no count pruning** (SPEC-CSP-API-003 / DEC-005).
  - `Session updateMeta(String id, title?, contextLabel?, contextSnapshot?)` — patch only non-null fields, bump `updatedAt`, rewrite shard + index row; `null` if absent (SPEC-CSP-API-004). Does not touch `messages`.
  - `DeleteResult delete(String id)` — delete shard + remove index row; if it was `activeSessionId`, reselect newest-by-`updatedAt` remaining else `null`; return `{deleted, id, activeSessionId}`; `null`/not-found signal for 404 (SPEC-CSP-API-005).
  - `List<Message> appendMessages(String id, List<Message> incoming)` — assign ids/`createdAt`, apply 009c then 009b, bump `updatedAt`, rewrite shard, refresh index row (`lastMessagePreview`/`messageCount`/`updatedAt`); `null` if session absent (SPEC-CSP-API-006).
  - `Message updateMessage(String id, String msgId, content?, status?, error?, suggestedTasks?)` — patch non-null fields (apply 009c on content), bump session `updatedAt`, rewrite shard + index row; `null` if session or message absent (SPEC-CSP-API-007).
  - `String setActiveSession(String idOrNull)` — persist pointer in `index.json`; caller validates existence for the 400 case (SPEC-CSP-API-008); store throws/returns signal if non-null id missing.
  - `void writeSummary(String id, String summary)` — write `summary` into shard + index row only; tolerate session deleted mid-flight (no-op). Used by async summary regeneration (SPEC-CSP-API-011a).
- **Concurrency note:** all mutating methods `synchronized` (single-process localhost backend; serialize load-modify-write to avoid lost updates between controller threads and the summary executor).

- [x] **Step 1: Class skeleton + static `ObjectMapper` + path fields** — in `ChatSessionStore.java`, add the `dir = memoryDir.resolve("chat-sessions")` / `indexFile` fields and the `TaskStore`-style `MAPPER` (JavaTimeModule, INDENT_OUTPUT, no timestamp dates). (SPEC-CSP-DEC-008)
- [x] **Step 2: Model classes** — add `Session`, `Message`, `SessionMeta` (public) and `Index` (private), all `@JsonIgnoreProperties(ignoreUnknown=true)`; `Instant` + `@JsonFormat(STRING)` timestamps; `contextSnapshot`/`suggestedTasks` as opaque `Object`/`List<Object>`. *(after Step 1)* (SPEC-CSP-MODEL-003, SPEC-CSP-MODEL-004)
- [x] **Step 3: Atomic `writeJson(target, value)`** — `createDirectories` → write `*.tmp` → `Files.move(..., REPLACE_EXISTING, ATOMIC_MOVE)`; `IOException` → `RuntimeException`. *(after Step 1)* (SPEC-CSP-API-010a)
- [x] **Step 4: `loadIndex` / `rebuildIndex` / `toMeta`** — `loadIndex` returns empty on missing, rebuilds (with warning, no silent delete) on parse failure; `rebuildIndex` scans shards (skip+warn per-shard failures), preserves resolvable `activeSessionId`; `toMeta` derives `lastMessagePreview`(~80 chars)/`messageCount`/`summary`. *(after Steps 2,3)* (SPEC-CSP-MODEL-005, SPEC-CSP-API-010c)
- [x] **Step 5: Read paths `listIndex()` + `getSession(id)`** — `listIndex` sorts `sessions` by `updatedAt` desc, empty dir → `{null, []}`; `getSession` returns shard or `null`. *(after Step 4)* (SPEC-CSP-API-001, SPEC-CSP-API-002)
- [x] **Step 6: `create(CreateRequest)`** — server-generated FS-safe `id` (`UUID...replace("-","")`), `createdAt`/`updatedAt`, defaults (`新会话`/`manual`), assign ids+`createdAt` to `initialMessages` (apply 009c→009b), write shard, set `activeSessionId=id`, upsert index row, **no count pruning**. *(after Step 5)* (SPEC-CSP-MODEL-001, SPEC-CSP-MODEL-002, SPEC-CSP-API-003, SPEC-CSP-DEC-005, SPEC-CSP-DEC-006)
- [x] **Step 7: `updateMeta(id, title?, contextLabel?, contextSnapshot?)`** — patch non-null fields only, bump `updatedAt`, rewrite shard + index row, never touch `messages`; `null` if absent. *(after Step 5)* (SPEC-CSP-API-004)
- [x] **Step 8: `delete(id)` → `DeleteResult`** — delete shard + remove index row; if it was active, reselect newest-by-`updatedAt` remaining else `null`; not-found signal for 404. *(after Step 5)* (SPEC-CSP-API-005, SPEC-CSP-DEC-006)
- [x] **Step 9: `appendMessages(id, incoming)` with invariants** — assign ids/`createdAt`, truncate `content`>20000 + append `...` (009c), keep newest 200 (009b), bump `updatedAt`, rewrite shard + refresh index row; `null` if session absent. *(after Step 5)* (SPEC-CSP-API-006, SPEC-CSP-API-009b, SPEC-CSP-API-009c)
- [x] **Step 10: `updateMessage(id, msgId, content?, status?, error?, suggestedTasks?)`** — patch non-null (apply 009c on `content`), bump session `updatedAt`, rewrite shard + index row; `null` if session/message absent. *(after Step 5)* (SPEC-CSP-API-007, SPEC-CSP-API-009c)
- [x] **Step 11: `setActiveSession(idOrNull)` + `writeSummary(id, summary)`** — `setActiveSession` persists pointer, signals invalid when non-null id missing (for caller's 400); `writeSummary` writes summary into shard + index row only, no-op if session gone. *(after Step 5)* (SPEC-CSP-API-008, SPEC-CSP-API-011a)
- [x] **Step 12: `synchronized` on all mutators + no-count-cap review** — confirm every write touches only `<id>.json` + `index.json` (per-shard isolation) and no path prunes by session count. *(after Steps 6–11)* (SPEC-CSP-API-009a, SPEC-CSP-API-010b)
- [ ] **Step 13: Compile + commit** — `mvn -q -pl self-analyst-app compile` clean; commit `SPEC-CSP-MODEL-001..005, SPEC-CSP-API-009, SPEC-CSP-API-010`.

**Verification:** `mvn -q -pl self-analyst-app compile` succeeds; full behavioral verification is Task 5 (SPEC-CSP-TST-001..014).

---

### Task 2: `ChatSummaryService` — LLM summary + deterministic fallback

**Files:**
- Create: `self-analyst-app/src/main/java/com/selfanalyst/desktop/service/ChatSummaryService.java`

Implements `SPEC-CSP-API-011b/d` (generation + privacy + fallback). Modeled on `SummaryPromptService` (same `SummaryTextClient` functional interface — reuse `SummaryPromptService.SummaryTextClient`, do **not** redefine it).

**How to implement:**
- `public String summarize(ChatSessionStore.Session session, SummaryPromptService.SummaryTextClient client)`:
  - Build prompt from session content **only** (titles + user/assistant message text); **never** include config values (SPEC-CSP-API-011d / DEC-007). Cap input length (e.g. last ~20 messages, each trimmed) to bound tokens.
  - If `client == null` → return `deterministicFallback(session)` (SPEC-CSP-API-011b).
  - Else `client.complete(prompt, Duration.ofSeconds(5))`; on null/blank/exception → `deterministicFallback`. Trim result to one line (~80 chars), strip code fences/quotes.
- `static String deterministicFallback(ChatSessionStore.Session s)` — concatenate the first few **user** messages' leading text (e.g. up to 3 snippets, joined by " / "), trimmed to ~80 chars; if none, fall back to `title`. Never empty (TST-015).
- Prompt text mirrors `SummaryPromptService.buildAdvicePrompt` style: short instruction, plain one-sentence Chinese output, no JSON required.

- [x] **Step 1: Class skeleton + `summarize` signature** — in `ChatSummaryService.java`, declare `public String summarize(ChatSessionStore.Session session, SummaryPromptService.SummaryTextClient client)`; reuse the existing `SummaryPromptService.SummaryTextClient` interface (do not redefine).
- [x] **Step 2: `buildPrompt(session)`** — assemble from session content only (title + user/assistant message text), cap to ~last 20 messages each trimmed; assert no config/secret values are referenced. *(after Step 1)* (SPEC-CSP-API-011d, SPEC-CSP-DEC-007, SPEC-CSP-DEC-010)
- [x] **Step 3: LLM path + guarding** — `client==null` → fallback; else `client.complete(prompt, Duration.ofSeconds(5))`, on null/blank/exception → fallback; normalize result to one line (~80 chars), strip fences/quotes. *(after Steps 1,2)* (SPEC-CSP-API-011b)
- [x] **Step 4: `deterministicFallback(session)`** — join leading text of first ≤3 **user** messages (" / "), trim ~80 chars, fall back to `title`; never empty. *(after Step 1)* (SPEC-CSP-API-011b)
- [ ] **Step 5: Compile + commit** — `mvn -q -pl self-analyst-app compile` clean; commit `SPEC-CSP-API-011b, SPEC-CSP-API-011d`.

**Verification:** `ChatSummaryServiceTest` in Task 5 (SPEC-CSP-TST-015) — fallback non-empty without a client; LLM stub returns one-line summary; throwing stub falls back.

---

### Task 3: `DesktopChatSessionController` — REST handlers + async summary

**Files:**
- Create: `self-analyst-app/src/main/java/com/selfanalyst/desktop/controller/DesktopChatSessionController.java`

Implements the request/response half of `SPEC-CSP-API-001..008`, `API-010c` (degrade), `API-011a/c` (async, non-blocking, allowed-to-lag).

**Constructor / wiring:** `DesktopChatSessionController(ChatSessionStore store, ChatSummaryService summaryService, SelfAnalystAgent agent, Config config)`. Static `ObjectMapper` with `JavaTimeModule` (copy `DesktopTaskController`). Error style: `try/catch` → `ctx.status(code).json(Map.of("error", msg))`, matching `DesktopTaskController`.

**Handlers (one per route):**
- `listSessions` → `store.listIndex()` as JSON (SPEC-CSP-API-001). Wrap `listIndex` so an index parse failure triggers rebuild rather than 500 (API-010c).
- `getSession` → `store.getSession(id)`; `null` → 404 (API-002).
- `createSession` → parse body into a `CreateRequest`; `store.create(...)`; **schedule summary** for the new id (if it has initial messages); 201 + full `Session` (API-003).
- `updateSession` → `store.updateMeta(...)`; `null` → 404; return `Session` (API-004).
- `deleteSession` → `store.delete(id)`; not-found → 404; cancel any pending summary task for id; return `{deleted,id,activeSessionId}` (API-005).
- `appendMessages` → accept single `Message` **or** `{messages:[...]}` (detect array vs object on parse); `store.appendMessages`; `null` → 404; **schedule summary**; 201 + appended messages (API-006).
- `updateMessage` → `store.updateMessage(...)`; `null` → 404; **schedule summary**; return `Message` (API-007).
- `setActiveSession` → parse `{activeSessionId}`; if non-null and `store.getSession(id)==null` → 400 with unchanged on-disk pointer; else `store.setActiveSession(...)`; return `{activeSessionId}` (API-008).

**Async summary regeneration (SPEC-CSP-API-011a/c):**
- Field: `ScheduledExecutorService summaryPool = Executors.newScheduledThreadPool(1, daemon thread "chat-summary")` (mirror `DesktopAgentController`'s daemon-thread pattern).
- `ConcurrentHashMap<String, ScheduledFuture<?>> pending` for **debounce/coalesce**: `scheduleSummary(id)` cancels any existing future for `id` and schedules a new one ~2–3s out. The worker: reload `Session` (skip if gone), compute LLM availability exactly as `DesktopAgentController` (lines 67–71: `agent != null && config.llmApiKey()` valid, no `CHANGE_ME`, `!agent.isBudgetBlocked()`), build `SummaryTextClient = agent::completePlain` or `null`, call `summaryService.summarize`, then `store.writeSummary(id, result)`. Catch-all so failures never propagate (API-011c). Never block the HTTP handler — scheduling returns immediately.
- Privacy: summary client uses the same `agent::completePlain` path already used for chat, so no new egress surface (DEC-010); config secrets are never passed to `summarize` (Task 2 guarantees).

- [x] **Step 1: Class skeleton + ctor + helpers** — in `DesktopChatSessionController.java`: fields (`store`, `summaryService`, `agent`, `config`), static `MAPPER` (+JavaTimeModule), `CreateRequest`/patch DTO parsing, and a `DesktopTaskController`-style error helper (`ctx.status(code).json(Map.of("error", msg))`).
- [x] **Step 2: `listSessions`** — `store.listIndex()` → JSON; an index parse failure rebuilds (via store) rather than 500. *(after Step 1; depends Task 1 Step 5)* (SPEC-CSP-API-001, SPEC-CSP-API-010c)
- [x] **Step 3: `getSession`** — `store.getSession(id)`; `null` → 404. *(after Step 1; Task 1 Step 5)* (SPEC-CSP-API-002, SPEC-CSP-TST-012)
- [x] **Step 4: `createSession`** — parse body, `store.create(...)`, `scheduleSummary(id)` when it has initial messages, 201 + full `Session`. *(after Steps 1,8; Task 1 Step 6)* (SPEC-CSP-API-003)
- [x] **Step 5: `updateSession`** — `store.updateMeta(...)`; `null` → 404; return `Session`. *(after Step 1; Task 1 Step 7)* (SPEC-CSP-API-004)
- [x] **Step 6: `deleteSession`** — `store.delete(id)`; not-found → 404; cancel pending summary future for id; return `{deleted,id,activeSessionId}`. *(after Steps 1,8; Task 1 Step 8)* (SPEC-CSP-API-005)
- [x] **Step 7: `appendMessages`** — accept single `Message` or `{messages:[...]}` (array-vs-object detect), `store.appendMessages`, `null` → 404, `scheduleSummary(id)`, 201 + appended. *(after Steps 1,8; Task 1 Step 9)* (SPEC-CSP-API-006)
- [x] **Step 8: Debounced summary pool + `scheduleSummary`/cancel** — `ScheduledExecutorService` (daemon "chat-summary"), `ConcurrentHashMap<String,ScheduledFuture<?>> pending`; `scheduleSummary(id)` cancels+reschedules ~2–3s; worker reloads `Session`, computes LLM availability exactly as `DesktopAgentController` (agent non-null, valid key sans `CHANGE_ME`, `!isBudgetBlocked()`), client = `agent::completePlain` or `null`, `summaryService.summarize` → `store.writeSummary`; catch-all, never blocks handlers. (SPEC-CSP-API-011a, SPEC-CSP-API-011c, SPEC-CSP-DEC-010)
- [x] **Step 9: `updateMessage`** — `store.updateMessage(...)`; `null` → 404; `scheduleSummary(id)`; return `Message`. *(after Steps 1,8; Task 1 Step 10)* (SPEC-CSP-API-007)
- [x] **Step 10: `setActiveSession`** — parse `{activeSessionId}`; non-null & `getSession==null` → 400 (on-disk pointer unchanged); else `store.setActiveSession`; return `{activeSessionId}`. *(after Step 1; Task 1 Step 11)* (SPEC-CSP-API-008, SPEC-CSP-TST-013)
- [ ] **Step 11: Compile + commit** — `mvn -q -pl self-analyst-app compile` clean; commit `SPEC-CSP-API-001..008, SPEC-CSP-API-010c, SPEC-CSP-API-011a, SPEC-CSP-API-011c`.

**Verification:** registered + exercised end-to-end in Task 4; 404/400 mapping covered by store-level null/invalid signals (Task 5 Step 5) and Task 9 manual `TST-017`.

---

### Task 4: Wire store/service/controller into `DesktopServer` + register routes

**Files:**
- Modify: `self-analyst-app/src/main/java/com/selfanalyst/desktop/DesktopServer.java`

**How to implement (mirror existing wiring at lines 73–84 / 110–134):**
- Imports: `ChatSessionStore`, `ChatSummaryService`, `DesktopChatSessionController`.
- In constructor after `TaskStore taskStore = new TaskStore(memoryDir);`:
  ```java
  ChatSessionStore chatSessionStore = new ChatSessionStore(memoryDir);
  ChatSummaryService chatSummaryService = new ChatSummaryService();
  this.chatSessionCtrl = new DesktopChatSessionController(
          chatSessionStore, chatSummaryService, agent, config);
  ```
  Add the `private final DesktopChatSessionController chatSessionCtrl;` field + a `chatSessionController()` getter (match the other controllers).
- In `start()`, under a new `// ── Chat sessions CRUD ──` block (place near the Agent tab block so it shares the `/desktop/chat` prefix):
  ```java
  app.get   ("/desktop/chat/sessions",                 chatSessionCtrl::listSessions);
  app.post  ("/desktop/chat/sessions",                 chatSessionCtrl::createSession);
  app.get   ("/desktop/chat/sessions/{id}",            chatSessionCtrl::getSession);
  app.put   ("/desktop/chat/sessions/{id}",            chatSessionCtrl::updateSession);
  app.delete("/desktop/chat/sessions/{id}",            chatSessionCtrl::deleteSession);
  app.post  ("/desktop/chat/sessions/{id}/messages",   chatSessionCtrl::appendMessages);
  app.put   ("/desktop/chat/sessions/{id}/messages/{msgId}", chatSessionCtrl::updateMessage);
  app.put   ("/desktop/chat/active-session",           chatSessionCtrl::setActiveSession);
  ```
  Ensure `POST /desktop/chat` (existing, `agentCtrl::chat`) and `GET /desktop/chat/sessions` do not collide — Javalin routes by method+path, and `/desktop/chat` vs `/desktop/chat/sessions` are distinct (SPEC-CSP-NON-005, SPEC-CSP-FE-008: existing chat contract untouched).

- [x] **Step 1: Imports + field + getter + construction** — in `DesktopServer.java`: import the three new types; add `private final DesktopChatSessionController chatSessionCtrl;` + `chatSessionController()` getter; construct `ChatSessionStore`/`ChatSummaryService`/controller in the ctor (after `taskStore`), passing the existing `agent` + `config`. *(depends Tasks 1–3 compiled)*
- [x] **Step 2: Register 8 routes** — add the `// ── Chat sessions CRUD ──` block in `start()` with the 8 `app.get/post/put/delete` mappings; verify `/desktop/chat` (existing `agentCtrl::chat`) and `/desktop/chat/sessions` do not collide. *(after Step 1)* (SPEC-CSP-API-001..008 registration, SPEC-CSP-NON-005, SPEC-CSP-FE-008)
- [ ] **Step 3: Compile + commit** — `mvn -q -pl self-analyst-app compile` clean; commit `SPEC-CSP-API-001, SPEC-CSP-API-008` (route registration).

**Verification:** start the app (Task 9) and `curl http://localhost:5700/desktop/chat/sessions` → `{"activeSessionId":null,"sessions":[]}` on a clean profile (SPEC-CSP-TST-001); existing `POST /desktop/chat` still answers (SPEC-CSP-NON-005).

---

### Task 5: Backend unit tests

**Files:**
- Create: `self-analyst-app/src/test/java/com/selfanalyst/desktop/store/ChatSessionStoreTest.java`
- Create: `self-analyst-app/src/test/java/com/selfanalyst/desktop/service/ChatSummaryServiceTest.java`

**`ChatSessionStoreTest`** (use JUnit 5 `@TempDir Path memoryDir`; covers SPEC-CSP-TST-001..014 at the store layer, leaving HTTP-status cases 012/013 for controller-level assertions where the store returns null/throws):
- `TST-001` — fresh `listIndex()` on empty dir → `activeSessionId==null`, `sessions` empty.
- `TST-002` — after create+append, `listIndex().sessions` items expose `summary`/`lastMessagePreview`/`messageCount` and carry **no** `messages` field.
- `TST-003` — `create` assigns id/`createdAt`/`updatedAt`, writes a standalone `<id>.json`, sets `activeSessionId`.
- `TST-004` — append user+pending then `getSession` → two ordered messages each with server id.
- `TST-005` — `updateMessage` pending→sent + `suggestedTasks` → status/content updated, session `updatedAt` advanced.
- `TST-006` — `updateMeta` title → title changed, `messages` untouched, index row synced.
- `TST-007` — delete active session → reselects newest remaining (or null), shard file gone.
- `TST-008` — create 200 sessions → all persisted/readable, none pruned (DEC-005).
- `TST-009` — append to A → only A's shard + `index.json` mtime/content change; B's shard byte-identical (isolation).
- `TST-010` — append >200 messages → only newest 200 retained.
- `TST-011` — append `content` >20000 chars → stored value truncated and ends with `...`.
- `TST-014` — corrupt/delete `index.json`, then `listIndex()` → rebuilt from shards, session bodies intact.

**`ChatSummaryServiceTest`** (SPEC-CSP-TST-015):
- Fallback: `summarize(session, null)` → non-empty deterministic text derived from user messages.
- LLM happy path: stub `SummaryTextClient` returning a sentence → returned (trimmed to one line); stub throwing → falls back, non-empty.

- [x] **Step 1: `ChatSessionStoreTest` scaffold** — JUnit 5 class with `@TempDir Path memoryDir` and a `new ChatSessionStore(memoryDir)` per test.
- [x] **Step 2: Core CRUD tests** — `TST-001` empty `listIndex`; `TST-002` meta has `summary`/`lastMessagePreview`/`messageCount` and no `messages`; `TST-003` `create` assigns id/timestamps + standalone `<id>.json` + active set; `TST-004` append user+pending ordered with server ids; `TST-005` `updateMessage` pending→sent + `suggestedTasks`, `updatedAt` advances; `TST-006` `updateMeta` title, messages untouched, index synced; `TST-007` delete active → reselect newest/null + shard removed. *(after Step 1)* (SPEC-CSP-TST-001..007)
- [x] **Step 3: Invariant + isolation tests** — `TST-008` 200 sessions all retained (no prune); `TST-009` append to A leaves B's shard byte-identical; `TST-010` >200 messages → newest 200; `TST-011` `content`>20000 → truncated ending `...`. *(after Step 1)* (SPEC-CSP-TST-008..011, SPEC-CSP-API-009)
- [x] **Step 4: Index-rebuild test** — `TST-014` delete/corrupt `index.json` then `listIndex()` rebuilds from shards, bodies intact. *(after Step 1)* (SPEC-CSP-TST-014, SPEC-CSP-MODEL-005)
- [x] **Step 5: Not-found / invalid-pointer signal tests** — `getSession`/`updateMeta`/`updateMessage`/`delete` on unknown id return `null` (controller's 404 source), and `setActiveSession` on a non-existent id signals invalid (controller's 400 source). *(after Step 1)* (SPEC-CSP-TST-012, SPEC-CSP-TST-013 preconditions)
- [x] **Step 6: `ChatSummaryServiceTest`** — `TST-015`: `summarize(session,null)` non-empty deterministic from user messages; stub client returning a sentence → one-line result; throwing stub → non-empty fallback. *(parallel to Steps 1–5)* (SPEC-CSP-TST-015)
- [x] **Step 7: Run green** — `mvn test -pl self-analyst-app -Dtest=ChatSessionStoreTest,ChatSummaryServiceTest` all pass. *(after Steps 2–6)*
- [ ] **Step 8: Commit** — `SPEC-CSP-TST-001..015`.

**Verification:** the two test classes pass under `mvn test`; HTTP-status cases `TST-012`/`TST-013` are asserted at the store-signal layer here and at the HTTP layer manually in Task 9.

---

### Task 6: Frontend `api.js` — session/message REST client (SPEC-CSP-FE-001)

**Files:**
- Modify: `self-analyst-app/src/main/resources/desktop-ui/api.js`

Add to the `api` object, mirroring existing fetch/`API_BASE`/error style (e.g. `createTask`). All return Promises; non-2xx → `throw new Error(...)`:
- `listSessions()` → `GET /desktop/chat/sessions`.
- `getSession(id)` → `GET /desktop/chat/sessions/{id}` (`encodeURIComponent(id)`).
- `createSession(body)` → `POST /desktop/chat/sessions`.
- `updateSession(id, patch)` → `PUT /desktop/chat/sessions/{id}`.
- `deleteSession(id)` → `DELETE /desktop/chat/sessions/{id}` → return parsed JSON (`{deleted,id,activeSessionId}`).
- `appendMessages(id, payload)` → `POST /desktop/chat/sessions/{id}/messages` (payload is `{messages:[...]}`).
- `updateMessage(id, msgId, patch)` → `PUT /desktop/chat/sessions/{id}/messages/{msgId}`.
- `setActiveSession(id)` → `PUT /desktop/chat/active-session` with `{activeSessionId:id}`.

- [x] **Step 1: Add 8 client methods** — in `api.js`, add `listSessions`/`getSession`/`createSession`/`updateSession`/`deleteSession`/`appendMessages`/`updateMessage`/`setActiveSession` mirroring the existing `createTask`/`deleteTask` fetch+error style (`API_BASE`, `encodeURIComponent(id)` on path params, non-2xx → `throw new Error`). `deleteSession`/`appendMessages`/`updateMessage`/`createSession`/`updateSession` parse and return JSON. (SPEC-CSP-FE-001)
- [ ] **Step 2: Commit** — `SPEC-CSP-FE-001`.

**Verification:** Task 9 static check asserts all 8 names exist in `api.js`; exercised live by Tasks 7–8 and manual `TST-017`.

---

### Task 7: Frontend `chat.js` — replace localStorage layer with REST (load/lazy/create/delete/rename/search)

**Files:**
- Modify: `self-analyst-app/src/main/resources/desktop-ui/chat.js`
- Modify: `self-analyst-app/src/main/resources/desktop-ui/state.js`

Implements `SPEC-CSP-FE-002..005`. The send/retry path is split into Task 8.

**How to implement:**
- `state.js`: keep `chatSessions`/`activeChatSessionId`/`chatSessionSearch`/`chatSending`. `CHAT_STORAGE_KEY` stays declared **only** for the one-time deletion in Task 8 — comment that it is no longer a data source.
- `loadChatSessions()` → **async**, returns a Promise: `api.listSessions()` → set `state.chatSessions` to the returned `SessionMeta` rows (each gets `messages=[]` and a `messagesLoaded=false` flag) and `state.activeChatSessionId` from the index (SPEC-CSP-FE-002). On failure, keep empty list + surface via existing error path (no throw that breaks init).
- `ensureSessionMessagesLoaded(session)` → new Promise helper: if `session.messagesLoaded`, resolve; else `api.getSession(id)` → copy `messages` + `summary` into the cached row, set `messagesLoaded=true` (SPEC-CSP-FE-003, lazy body load).
- `getActiveChatSession()` unchanged (in-memory lookup).
- Session selection (in `renderChatSessionList`'s click handler) → set `state.activeChatSessionId`, call `api.setActiveSession(id)` (fire-and-forget with catch), `ensureSessionMessagesLoaded(session).then(renderChatTab)` so the thread renders once bodies arrive (replaces the old `saveChatSessions()` call).
- `createChatSession(opts)` → **async**: `api.createSession({title,source,contextLabel,contextSnapshot, initialMessages})` → unshift returned `Session` (with `messagesLoaded=true`) into `state.chatSessions`, set `activeChatSessionId`. Callers that used the return synchronously (`ensureActiveChatSession`, `openChatTabWithContext`, events.js) must consume the Promise.
- `ensureActiveChatSession()` → returns Promise resolving to a session (await create when none).
- `deleteChatSession(id)` → `api.deleteSession(id)` → on success remove from `state.chatSessions` and set `state.activeChatSessionId` from response's `activeSessionId`; then `renderChatTab()` (SPEC-CSP-FE-004 delete).
- Rename / first-message title backfill / context binding → call `api.updateSession(id, patch)` (replace direct mutation + `saveChatSessions()`); update the cached row from the response (SPEC-CSP-FE-004 rename).
- **Remove** `saveChatSessions()` and `touchSession()` as persistence (server owns `updatedAt`/ordering). Where ordering mattered, re-sort `state.chatSessions` by `updatedAt` desc after writes, or re-`listSessions()` for the list view — prefer local re-sort to avoid extra round-trips; list order is reconciled on next `listSessions()`.
- `renderChatSessionList` search filter → match `title` + `lastMessagePreview` + `summary` (lowercased), **not** message bodies (SPEC-CSP-FE-005 / DEC-009). Preview already comes from `lastMessagePreview` (fallback to last loaded message if present).
- `renderChatThread` — when active session not yet loaded, show a lightweight "加载中…" state and trigger `ensureSessionMessagesLoaded`.

- [x] **Step 1: `state.js` annotation** — keep `chatSessions`/`activeChatSessionId`/`chatSessionSearch`/`chatSending`; add a comment on `CHAT_STORAGE_KEY` that it is retained **only** for the one-time deletion (Task 8) and is no longer a data source. (SPEC-CSP-DEC-002)
- [x] **Step 2: Async `loadChatSessions()` + `ensureSessionMessagesLoaded(session)`** — `loadChatSessions` returns a Promise: `api.listSessions()` → fill `state.chatSessions` (each row `messages:[]`, `messagesLoaded:false`) + `state.activeChatSessionId`; failure keeps empty list without throwing. `ensureSessionMessagesLoaded` lazy-loads bodies via `api.getSession(id)` and sets `messagesLoaded:true`. *(after Step 1; depends Task 6)* (SPEC-CSP-FE-002, SPEC-CSP-FE-003)
- [x] **Step 3: `createChatSession`/`ensureActiveChatSession` async** — `createChatSession` calls `api.createSession(...)`, unshifts the returned `Session` (`messagesLoaded:true`), sets active, returns a Promise; `ensureActiveChatSession` resolves to a session (awaiting create when none). Update `openChatTabWithContext` and any caller to consume the Promise. *(after Step 2)* (SPEC-CSP-FE-004 create)
- [x] **Step 4: `deleteChatSession` + session selection via REST** — `deleteChatSession` awaits `api.deleteSession(id)`, removes the row, sets `activeChatSessionId` from the response, re-renders; the list click handler sets active, fires `api.setActiveSession(id)` (catch), then `ensureSessionMessagesLoaded(session).then(renderChatTab)`. Remove the old `saveChatSessions()` calls here. *(after Step 2)* (SPEC-CSP-FE-004 delete/active)
- [x] **Step 5: Rename / title-backfill / context-bind via `updateSession`; drop `saveChatSessions`/`touchSession`** — replace direct mutation + `saveChatSessions()` with `api.updateSession(id, patch)` and update the cached row from the response; delete `saveChatSessions`/`touchSession`; re-sort `state.chatSessions` by `updatedAt` desc locally after writes. *(after Step 2)* (SPEC-CSP-FE-004 rename, SPEC-CSP-DEC-002)
- [x] **Step 6: Summary-based search + lazy thread render** — `renderChatSessionList` filter matches `title` + `lastMessagePreview` + `summary` (lowercased), not message bodies; `renderChatThread` shows "加载中…" + triggers `ensureSessionMessagesLoaded` when the active session is unloaded. *(after Step 2)* (SPEC-CSP-FE-005, SPEC-CSP-DEC-009)
- [ ] **Step 7: Commit** — `SPEC-CSP-FE-002, SPEC-CSP-FE-003, SPEC-CSP-FE-004 (create/delete/rename/active), SPEC-CSP-FE-005`.

**Verification:** Task 9 static check (search references `lastMessagePreview`+`summary`, no `localStorage.setItem(CHAT_STORAGE_KEY`); manual `TST-016` (search hits summary) after Task 8 wires init.

---

### Task 8: Frontend send/retry over REST + legacy-key cleanup + async init

**Files:**
- Modify: `self-analyst-app/src/main/resources/desktop-ui/chat.js`
- Modify: `self-analyst-app/src/main/resources/desktop-ui/init.js`
- Modify: `self-analyst-app/src/main/resources/desktop-ui/events.js`

Implements `SPEC-CSP-FE-004 (send)`, `FE-006`, `FE-007`, `FE-008`.

**Send flow (`sendChatTabMessage`, SPEC-CSP-FE-004 send / FE-007):**
1. Resolve session via `ensureActiveChatSession()` (Promise).
2. `api.appendMessages(id, {messages:[userMsg, pendingAssistant]})` where `pendingAssistant` has `status:"pending"`. Use the **server-returned** ids for the two messages; push them into the cached `session.messages`; re-render.
3. Title backfill via `api.updateSession` if still default (reuse Task 7 helper).
4. `api.postChat(text, context)` — **unchanged** contract (SPEC-CSP-FE-008).
5. On success → `api.updateMessage(id, pendingId, {status:"sent", content, suggestedTasks})`; update cache + re-render.
6. On failure → `api.updateMessage(id, pendingId, {status:"error", error})` (best-effort) and mark the cached message error so the user's input is never lost; keep the retry affordance (SPEC-CSP-FE-007). If even the persistence call fails, still reflect error in-memory.
- `retryChatMessage` — same shape: `updateMessage(...,{status:"pending"})` → `postChat` → `updateMessage` sent/error.
- Guard `state.chatSending` exactly as today to serialize sends.

**Legacy key cleanup (`init.js`, SPEC-CSP-FE-006 / DEC-004):**
- In `init()`, before/around the session load: `try { localStorage.removeItem(CHAT_STORAGE_KEY); } catch(e){}`. Never read it again.
- Make init await the index: `loadChatSessions().then(function(){ renderChatTab(); })` (or sequence inside `loadAll`) so the first chat render runs after the index resolves (SPEC-CSP-FE-002 "索引加载完成后再渲染").

**events.js:**
- New-session button → `createChatSession({title:"新会话"}).then(function(){ switchTab("chat"); renderChatTab(); focus input; })`.
- Send button / Enter key → call the now-Promise-based `sendChatTabMessage` (no signature change needed; internal awaits).

- [x] **Step 1: Rewrite `sendChatTabMessage`** — resolve session via `ensureActiveChatSession()`; `api.appendMessages(id,{messages:[userMsg, pendingAssistant]})`, adopt server ids into cache, render; title-backfill via `api.updateSession` (reuse Task 7 helper); `api.postChat(text, context)` **unchanged**; on success `api.updateMessage(id,pendingId,{status:"sent",content,suggestedTasks})`; on failure `api.updateMessage(...,{status:"error",error})` best-effort, never lose the typed input, keep retry; guard `state.chatSending`. *(depends Tasks 6,7)* (SPEC-CSP-FE-004 send, SPEC-CSP-FE-007, SPEC-CSP-FE-008)
- [x] **Step 2: Rewrite `retryChatMessage`** — `api.updateMessage(...,{status:"pending"})` → `api.postChat` → `updateMessage` sent/error; same `chatSending` guard. *(after Step 1)* (SPEC-CSP-FE-004 send, SPEC-CSP-FE-007)
- [x] **Step 3: Async init + legacy-key deletion in `init.js`** — `try{ localStorage.removeItem(CHAT_STORAGE_KEY); }catch(e){}`; sequence first chat render after `loadChatSessions()` resolves (`loadChatSessions().then(renderChatTab)`), never reading the old key. *(after Step 1)* (SPEC-CSP-FE-002, SPEC-CSP-FE-006, SPEC-CSP-DEC-004)
- [x] **Step 4: `events.js` promise-based handlers** — new-session button: `createChatSession({title:"新会话"}).then(()=>{ switchTab("chat"); renderChatTab(); focus; })`; send button / Enter call the now-async `sendChatTabMessage`. *(after Steps 1,3)* (SPEC-CSP-FE-004)
- [ ] **Step 5: Commit** — `SPEC-CSP-FE-004 (send), SPEC-CSP-FE-006, SPEC-CSP-FE-007, SPEC-CSP-FE-008`.

**Verification:** manual `TST-017` (Network shows append→chat→updateMessage, persists across refresh) and `TST-018` (old key deleted, never read) in Task 9.

---

### Task 9: Static check script + full build/manual verification

**Files:**
- Create: `scripts/check-desktop-chat-session-store.ps1`

Model on `scripts/check-desktop-behavior-advice.ps1` (`Assert-Contains` helper, `$Root = Split-Path -Parent $PSScriptRoot`). Assert:
- `api.js` contains each of `listSessions`, `getSession`, `createSession`, `updateSession`, `deleteSession`, `appendMessages`, `updateMessage`, `setActiveSession` (SPEC-CSP-FE-001).
- `chat.js` references `/desktop/chat/sessions` usage via `api.` calls and **does not** contain `localStorage.setItem(CHAT_STORAGE_KEY` (source-of-truth removed).
- `init.js` contains `localStorage.removeItem(CHAT_STORAGE_KEY)` (SPEC-CSP-FE-006).
- `chat.js` search filter references `lastMessagePreview` and `summary` (SPEC-CSP-FE-005), not message-body full-text scan.

**Manual / acceptance (SPEC-CSP-TST-016..018), run the desktop app:**
- `TST-016` — type a word present in a session `summary` → list filters to it.
- `TST-017` — send a message; in devtools Network observe `POST .../messages` → `POST /desktop/chat` → `PUT .../messages/{id}`; refresh → message persists.
- `TST-018` — first load after upgrade does not read the old key and removes it (check `localStorage`).

- [x] **Step 1: Write `scripts/check-desktop-chat-session-store.ps1`** — `Assert-Contains` helper + `$Root = Split-Path -Parent $PSScriptRoot`; assert the 8 `api.js` method names; `chat.js` has no `localStorage.setItem(CHAT_STORAGE_KEY` and its search references `lastMessagePreview` + `summary`; `init.js` has `localStorage.removeItem(CHAT_STORAGE_KEY)`. (SPEC-CSP-FE-001, FE-005, FE-006)
- [x] **Step 2: Run the check script** — `powershell -ExecutionPolicy Bypass -File scripts/check-desktop-chat-session-store.ps1` → "passed". *(after Step 1; depends Tasks 6–8)*
- [x] **Step 3: Full build + regression** — `mvn test` BUILD SUCCESS; `scripts/check-desktop-chat-tab.ps1` still passes. *(after Tasks 1–8)* (SPEC-CSP-NON-001)
- [ ] **Step 4: Manual acceptance against the running app** — `TST-016` search hits a session `summary`; `TST-017` Network shows `POST .../messages` → `POST /desktop/chat` → `PUT .../messages/{id}` and the message survives refresh; `TST-018` old `localStorage` key is absent/removed and never read. *(after Steps 2,3)* (SPEC-CSP-TST-016, SPEC-CSP-TST-017, SPEC-CSP-TST-018)
- [ ] **Step 5: Update spec traceability matrix + commit** — point the `docs/specs/chat-session-store.md` matrix rows at the real files (`ChatSessionStore.java`, `ChatSummaryService.java`, `DesktopChatSessionController.java`, etc.); commit.

**Verification:** check script prints "passed"; `mvn test` green; the three manual acceptance scenarios observed.

---

### Traceability Matrix (plan coverage)

| Spec ID | Task(s) |
|---------|---------|
| SPEC-CSP-DEC-001..010 | Tasks 1–8 (design realized); DEC-004/006/008/009/010 explicitly in 1,3,7,8 |
| SPEC-CSP-GOAL-001..006 | All tasks |
| SPEC-CSP-MODEL-001..005 | Task 1 |
| SPEC-CSP-API-001..002 | Tasks 1, 3, 4 |
| SPEC-CSP-API-003..005 | Tasks 1, 3 |
| SPEC-CSP-API-006..007 | Tasks 1, 3 |
| SPEC-CSP-API-008 | Tasks 1, 3, 4 |
| SPEC-CSP-API-009 | Task 1 |
| SPEC-CSP-API-010 | Tasks 1 (a/b), 3 (c) |
| SPEC-CSP-API-011 | Tasks 2 (b/d), 3 (a/c) |
| SPEC-CSP-FE-001 | Task 6 |
| SPEC-CSP-FE-002..003 | Task 7 |
| SPEC-CSP-FE-004 | Tasks 7 (create/delete/rename/active), 8 (send) |
| SPEC-CSP-FE-005 | Task 7 |
| SPEC-CSP-FE-006..008 | Task 8 |
| SPEC-CSP-NON-001..007 | Tasks 4, 7, 8 (code review: contracts/layout unchanged) |
| SPEC-CSP-TST-001..014 | Task 5 (store) + Task 3 status cases (012/013) |
| SPEC-CSP-TST-015 | Task 5 (ChatSummaryServiceTest) |
| SPEC-CSP-TST-016..018 | Task 9 (manual) |
