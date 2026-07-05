package com.selfanalyst.desktop.controller;

import com.selfanalyst.agent.SelfAnalystAgent;
import com.selfanalyst.config.Config;
import com.selfanalyst.desktop.service.ChatSummaryService;
import com.selfanalyst.desktop.service.MemoryExtractionService;
import com.selfanalyst.desktop.service.SummaryPromptService;
import com.selfanalyst.desktop.store.ChatSessionStore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * REST CRUD for desktop chat sessions (SPEC-CSP-API-001..008), backed by
 * {@link ChatSessionStore}. Mirrors {@link DesktopTaskController}'s error style
 * ({@code ctx.status(code).json(Map.of("error", msg))}).
 *
 * <pre>
 *   GET    /desktop/chat/sessions                       → list index
 *   POST   /desktop/chat/sessions                       → create
 *   GET    /desktop/chat/sessions/{id}                  → full session
 *   PUT    /desktop/chat/sessions/{id}                  → update meta
 *   DELETE /desktop/chat/sessions/{id}                  → delete
 *   POST   /desktop/chat/sessions/{id}/messages         → append message(s)
 *   PUT    /desktop/chat/sessions/{id}/messages/{msgId} → update message
 *   PUT    /desktop/chat/active-session                 → set active pointer
 * </pre>
 *
 * After session content changes the controller schedules a debounced,
 * best-effort summary regeneration that never blocks the HTTP handler
 * (SPEC-CSP-API-011a/c).
 */
public class DesktopChatSessionController {

    private static final Logger log = LoggerFactory.getLogger(DesktopChatSessionController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    /** Debounce window before a summary is (re)generated. */
    private static final long SUMMARY_DEBOUNCE_MS = 2500;

    private final ChatSessionStore store;
    private final ChatSummaryService summaryService;
    private final SelfAnalystAgent agent;
    private final Config config;
    private final MemoryExtractionService memoryExtractionService;

    private final ScheduledExecutorService summaryPool = Executors.newScheduledThreadPool(
            1, r -> { Thread t = new Thread(r, "chat-summary"); t.setDaemon(true); return t; });
    private final ScheduledExecutorService memoryPool = Executors.newScheduledThreadPool(
            1, r -> { Thread t = new Thread(r, "memory-extraction"); t.setDaemon(true); return t; });
    private final ConcurrentHashMap<String, ScheduledFuture<?>> pending = new ConcurrentHashMap<>();

    public DesktopChatSessionController(ChatSessionStore store,
                                        ChatSummaryService summaryService,
                                        SelfAnalystAgent agent,
                                        Config config,
                                        MemoryExtractionService memoryExtractionService) {
        this.store = store;
        this.summaryService = summaryService;
        this.agent = agent;
        this.config = config;
        this.memoryExtractionService = memoryExtractionService;
    }

    // ── Handlers ─────────────────────────────────────────────────

    /** GET /desktop/chat/sessions (SPEC-CSP-API-001; rebuild-on-corrupt via store, SPEC-CSP-API-010c). */
    public void listSessions(Context ctx) {
        try {
            ctx.json(store.listIndex());
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", "Failed to list sessions: " + e.getMessage()));
        }
    }

    /** GET /desktop/chat/sessions/{id} (SPEC-CSP-API-002). */
    public void getSession(Context ctx) {
        try {
            String id = ctx.pathParam("id");
            ChatSessionStore.Session s = store.getSession(id);
            if (s == null) {
                ctx.status(404).json(Map.of("error", "Session not found: " + id));
                return;
            }
            ctx.json(s);
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", "Failed to get session: " + e.getMessage()));
        }
    }

    /** POST /desktop/chat/sessions (SPEC-CSP-API-003). */
    public void createSession(Context ctx) {
        try {
            ChatSessionStore.CreateRequest req = ctx.body().isBlank()
                    ? new ChatSessionStore.CreateRequest()
                    : MAPPER.readValue(ctx.body(), ChatSessionStore.CreateRequest.class);
            ChatSessionStore.Session created = store.create(req);
            if (created.messages != null && !created.messages.isEmpty()) {
                scheduleSummary(created.id);
            }
            ctx.status(201).json(created);
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", "Failed to create session: " + e.getMessage()));
        }
    }

    /** PUT /desktop/chat/sessions/{id} (SPEC-CSP-API-004). */
    public void updateSession(Context ctx) {
        try {
            String id = ctx.pathParam("id");
            JsonNode body = MAPPER.readTree(ctx.body());
            String title = textOrNull(body, "title");
            String contextLabel = textOrNull(body, "contextLabel");
            Object contextSnapshot = body.has("contextSnapshot") && !body.get("contextSnapshot").isNull()
                    ? MAPPER.convertValue(body.get("contextSnapshot"), Object.class) : null;
            ChatSessionStore.Session updated = store.updateMeta(id, title, contextLabel, contextSnapshot);
            if (updated == null) {
                ctx.status(404).json(Map.of("error", "Session not found: " + id));
                return;
            }
            ctx.json(updated);
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", "Failed to update session: " + e.getMessage()));
        }
    }

    /** DELETE /desktop/chat/sessions/{id} (SPEC-CSP-API-005). */
    public void deleteSession(Context ctx) {
        try {
            String id = ctx.pathParam("id");
            ChatSessionStore.DeleteResult result = store.delete(id);
            if (result == null) {
                ctx.status(404).json(Map.of("error", "Session not found: " + id));
                return;
            }
            cancelSummary(id);
            Map<String, Object> resp = new java.util.HashMap<>();
            resp.put("deleted", result.deleted());
            resp.put("id", result.id());
            resp.put("activeSessionId", result.activeSessionId()); // may be null
            ctx.json(resp);
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", "Failed to delete session: " + e.getMessage()));
        }
    }

    /** POST /desktop/chat/sessions/{id}/messages (SPEC-CSP-API-006). */
    public void appendMessages(Context ctx) {
        try {
            String id = ctx.pathParam("id");
            List<ChatSessionStore.Message> incoming = parseMessages(ctx.body());
            List<ChatSessionStore.Message> appended = store.appendMessages(id, incoming);
            if (appended == null) {
                ctx.status(404).json(Map.of("error", "Session not found: " + id));
                return;
            }
            scheduleSummary(id);
            ctx.status(201).json(appended);
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", "Failed to append messages: " + e.getMessage()));
        }
    }

    /** PUT /desktop/chat/sessions/{id}/messages/{msgId} (SPEC-CSP-API-007). */
    public void updateMessage(Context ctx) {
        try {
            String id = ctx.pathParam("id");
            String msgId = ctx.pathParam("msgId");
            JsonNode body = MAPPER.readTree(ctx.body());
            String content = textOrNull(body, "content");
            String status = textOrNull(body, "status");
            String error = textOrNull(body, "error");
            List<Object> suggestedTasks = body.has("suggestedTasks") && body.get("suggestedTasks").isArray()
                    ? MAPPER.convertValue(body.get("suggestedTasks"), new TypeReference<List<Object>>() {}) : null;
            ChatSessionStore.Message updated =
                    store.updateMessage(id, msgId, content, status, error, suggestedTasks);
            if (updated == null) {
                ctx.status(404).json(Map.of("error", "Session or message not found"));
                return;
            }
            scheduleSummary(id);
            if ("assistant".equals(updated.role) && "sent".equals(updated.status)) {
                scheduleMemoryExtraction(id, updated.id);
            }
            ctx.json(updated);
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", "Failed to update message: " + e.getMessage()));
        }
    }

    /** PUT /desktop/chat/sessions/{id}/memory-policy */
    public void setMemoryPolicy(Context ctx) {
        try {
            String id = ctx.pathParam("id");
            JsonNode body = MAPPER.readTree(ctx.body());
            String policy = textOrNull(body, "memoryPolicy");
            ChatSessionStore.Session updated = store.updateMemoryPolicy(id, policy);
            if (updated == null) {
                ctx.status(404).json(Map.of("error", "Session not found: " + id));
                return;
            }
            ctx.json(updated);
        } catch (IllegalArgumentException e) {
            ctx.status(400).json(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", "Failed to set memory policy: " + e.getMessage()));
        }
    }

    /** PUT /desktop/chat/active-session (SPEC-CSP-API-008). */
    public void setActiveSession(Context ctx) {
        try {
            JsonNode body = MAPPER.readTree(ctx.body());
            String activeId = textOrNull(body, "activeSessionId");
            if (activeId != null && store.getSession(activeId) == null) {
                ctx.status(400).json(Map.of("error", "Unknown session: " + activeId));
                return;
            }
            String persisted = store.setActiveSession(activeId);
            Map<String, Object> resp = new java.util.HashMap<>();
            resp.put("activeSessionId", persisted); // may be null
            ctx.json(resp);
        } catch (IllegalArgumentException e) {
            ctx.status(400).json(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", "Failed to set active session: " + e.getMessage()));
        }
    }

    // ── Async summary (SPEC-CSP-API-011a/c) ──────────────────────

    /** Cancel any pending summary for {@code id} and reschedule ~2.5s out (debounce/coalesce). */
    private void scheduleSummary(String id) {
        if (id == null) return;
        cancelSummary(id);
        ScheduledFuture<?> future = summaryPool.schedule(
                () -> regenerateSummary(id), SUMMARY_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
        pending.put(id, future);
    }

    private void cancelSummary(String id) {
        if (id == null) return;
        ScheduledFuture<?> existing = pending.remove(id);
        if (existing != null) existing.cancel(false);
    }

    /** Worker: reload session, compute the summary (LLM or fallback), write it back. Never throws. */
    private void regenerateSummary(String id) {
        pending.remove(id);
        try {
            ChatSessionStore.Session session = store.getSession(id);
            if (session == null) return; // deleted mid-flight
            // LLM availability computed exactly as DesktopAgentController.getSummary.
            boolean llmAvailable = agent != null && config != null
                    && config.llmApiKey() != null && !config.llmApiKey().isBlank()
                    && !config.llmApiKey().contains("CHANGE_ME")
                    && !agent.isBudgetBlocked();
            SummaryPromptService.SummaryTextClient client = llmAvailable ? agent::completePlain : null;
            String summary = summaryService.summarize(session, client);
            if (summary != null && !summary.isBlank()) {
                store.writeSummary(id, summary);
            }
        } catch (Exception e) {
            // best-effort: failures must never propagate (SPEC-CSP-API-011c)
            log.warn("Summary regeneration failed for {}: {}", id, e.getMessage());
        }
    }

    private void scheduleMemoryExtraction(String sessionId, String assistantMessageId) {
        if (memoryExtractionService == null) return;
        memoryPool.submit(() -> {
            try {
                ChatSessionStore.Session session = store.getSession(sessionId);
                if (session == null || "off".equals(session.memoryPolicy) || session.messages == null) return;
                ChatSessionStore.Message assistant = null;
                ChatSessionStore.Message user = null;
                for (int i = 0; i < session.messages.size(); i++) {
                    ChatSessionStore.Message message = session.messages.get(i);
                    if (assistantMessageId.equals(message.id)) {
                        assistant = message;
                        for (int j = i - 1; j >= 0; j--) {
                            ChatSessionStore.Message previous = session.messages.get(j);
                            if ("user".equals(previous.role)) {
                                user = previous;
                                break;
                            }
                        }
                        break;
                    }
                }
                if (assistant == null || user == null) return;
                SummaryPromptService.SummaryTextClient client = llmAvailable() ? agent::completePlain : null;
                memoryExtractionService.extractAfterAssistantSent(session, user, assistant, client);
            } catch (Exception e) {
                log.warn("Memory extraction failed for session {}: {}", sessionId, e.getMessage());
            }
        });
    }

    private boolean llmAvailable() {
        return agent != null && config != null
                && config.llmApiKey() != null && !config.llmApiKey().isBlank()
                && !config.llmApiKey().contains("CHANGE_ME")
                && !agent.isBudgetBlocked();
    }

    // ── Parsing helpers ──────────────────────────────────────────

    /** Accept a single {@code Message} object or {@code {messages:[...]}} (SPEC-CSP-API-006). */
    private static List<ChatSessionStore.Message> parseMessages(String body) throws Exception {
        JsonNode node = MAPPER.readTree(body);
        if (node != null && node.isObject() && node.has("messages") && node.get("messages").isArray()) {
            return MAPPER.convertValue(node.get("messages"),
                    new TypeReference<List<ChatSessionStore.Message>>() {});
        }
        if (node != null && node.isArray()) {
            return MAPPER.convertValue(node, new TypeReference<List<ChatSessionStore.Message>>() {});
        }
        List<ChatSessionStore.Message> single = new ArrayList<>();
        if (node != null && node.isObject()) {
            single.add(MAPPER.convertValue(node, ChatSessionStore.Message.class));
        }
        return single;
    }

    private static String textOrNull(JsonNode body, String field) {
        JsonNode n = body != null ? body.get(field) : null;
        return n != null && !n.isNull() ? n.asText() : null;
    }
}
