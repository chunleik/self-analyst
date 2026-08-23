package com.selfanalyst.desktop.controller;

import com.selfanalyst.agent.SelfAnalystAgent;
import com.selfanalyst.config.Config;
import com.selfanalyst.desktop.service.ChatSummaryService;
import com.selfanalyst.desktop.service.MemoryExtractionService;
import com.selfanalyst.desktop.service.SummaryPromptService;
import com.selfanalyst.desktop.store.ChatSessionStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.http.Context;
import io.javalin.http.HttpResponseException;
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
    private static final ObjectMapper MAPPER = DesktopChatJson.MAPPER;

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
            String limitValue = ctx.queryParam("limit");
            String cursor = ctx.queryParam("cursor");
            String query = ctx.queryParam("q");
            if (limitValue == null && cursor == null && query == null) {
                ChatSessionStore.Index index = store.listIndex();
                Map<String, Object> legacy = new java.util.LinkedHashMap<>();
                legacy.put("activeSessionId", index.activeSessionId);
                legacy.put("sessions", index.sessions);
                ctx.json(legacy);
                return;
            }
            if (limitValue != null && limitValue.isBlank()) {
                throw new IllegalArgumentException("limit must not be blank");
            }
            int limit = limitValue == null ? 50 : Integer.parseInt(limitValue);
            ctx.json(store.listIndexPage(limit, cursor, query));
        } catch (IllegalArgumentException e) {
            ctx.status(400).json(Map.of("error",
                    e.getMessage() == null ? "Invalid chat index query" : e.getMessage()));
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", "Failed to list sessions: " + e.getMessage()));
        }
    }

    /** GET /desktop/chat/sessions/{id} (SPEC-CSP-API-002). */
    public void getSession(Context ctx) {
        try {
            String id = ctx.pathParam("id");
            if (!requireGeneratedSessionId(ctx, id)) return;
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
            String rawBody = DesktopChatJson.readBoundedBody(ctx);
            ChatSessionStore.CreateRequest req;
            if (rawBody.isBlank()) {
                req = new ChatSessionStore.CreateRequest();
            } else {
                JsonNode body = requireObject(MAPPER.readTree(rawBody));
                requireTextFields(body, "title", "source", "contextLabel", "memoryPolicy");
                requireContainerOrNull(body, "contextSnapshot");
                if (body.has("initialMessages") && !body.get("initialMessages").isNull()
                        && !body.get("initialMessages").isArray()) {
                    throw new IllegalArgumentException("initialMessages must be an array");
                }
                if (body.has("initialMessages") && body.get("initialMessages").isArray()
                        && body.get("initialMessages").isEmpty()) {
                    throw new IllegalArgumentException("initialMessages must not be empty");
                }
                req = MAPPER.treeToValue(body, ChatSessionStore.CreateRequest.class);
            }
            ChatSessionStore.Session created = store.create(req);
            if (created.messages != null && !created.messages.isEmpty()) {
                scheduleSummary(created.id);
            }
            ctx.status(201).json(created);
        } catch (Exception e) {
            if (writeClientError(ctx, e)) return;
            ctx.status(500).json(Map.of("error", "Failed to create session: " + e.getMessage()));
        }
    }

    /** PUT /desktop/chat/sessions/{id} (SPEC-CSP-API-004). */
    public void updateSession(Context ctx) {
        try {
            String id = ctx.pathParam("id");
            if (!requireGeneratedSessionId(ctx, id)) return;
            JsonNode body = requireObject(MAPPER.readTree(DesktopChatJson.readBoundedBody(ctx)));
            requireTextFields(body, "title", "contextLabel");
            requireContainerOrNull(body, "contextSnapshot");
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
            if (writeClientError(ctx, e)) return;
            ctx.status(500).json(Map.of("error", "Failed to update session: " + e.getMessage()));
        }
    }

    /** DELETE /desktop/chat/sessions/{id} (SPEC-CSP-API-005). */
    public void deleteSession(Context ctx) {
        try {
            String id = ctx.pathParam("id");
            if (!requireGeneratedSessionId(ctx, id)) return;
            if (store.getSession(id) == null) {
                ctx.status(404).json(Map.of("error", "Session not found: " + id));
                return;
            }
            ChatSessionStore.DeleteResult result;
            if (agent != null) {
                try {
                    // Both mutations stay inside the same gate used by chat execution. A chat can
                    // therefore run before deletion or be rejected after it, but cannot recreate
                    // hidden state between the state and transcript deletes.
                    result = agent.deleteChatSessionStateThen(id, () -> store.delete(id));
                } catch (IllegalStateException busy) {
                    if (DesktopAgentController.hasAgentStillRunning(busy)) {
                        ctx.status(409).json(Map.of(
                                "error", "Session is currently processing a chat request"));
                        return;
                    }
                    throw busy;
                }
            } else {
                if (config == null) {
                    throw new IllegalStateException(
                            "Cannot locate persisted AgentState without application config");
                }
                // Agent construction is optional. Privacy deletion is not: remove the same
                // file-backed state directly and retain the transcript if that removal fails.
                SelfAnalystAgent.deletePersistedChatSessionState(config.memoryDir(), id);
                result = store.delete(id);
            }
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
            if (!requireGeneratedSessionId(ctx, id)) return;
            List<ChatSessionStore.Message> incoming = parseMessages(
                    DesktopChatJson.readBoundedBody(ctx));
            List<ChatSessionStore.Message> appended = store.appendMessages(id, incoming);
            if (appended == null) {
                ctx.status(404).json(Map.of("error", "Session not found: " + id));
                return;
            }
            scheduleSummary(id);
            ctx.status(201).json(appended);
        } catch (Exception e) {
            if (writeClientError(ctx, e)) return;
            ctx.status(500).json(Map.of("error", "Failed to append messages: " + e.getMessage()));
        }
    }

    /** PUT /desktop/chat/sessions/{id}/messages/{msgId} (SPEC-CSP-API-007). */
    public void updateMessage(Context ctx) {
        try {
            String id = ctx.pathParam("id");
            if (!requireGeneratedSessionId(ctx, id)) return;
            String msgId = ctx.pathParam("msgId");
            if (!ChatSessionStore.isGeneratedMessageId(msgId)) {
                ctx.status(400).json(Map.of("error", "Invalid chat message id"));
                return;
            }
            JsonNode body = requireObject(MAPPER.readTree(DesktopChatJson.readBoundedBody(ctx)));
            requireTextFields(body, "content", "status", "error");
            String content = textOrNull(body, "content");
            String status = textOrNull(body, "status");
            String error = textOrNull(body, "error");
            String previousStatus = messageStatus(id, msgId);
            List<Object> suggestedTasks = null;
            if (body.has("suggestedTasks")) {
                if (body.get("suggestedTasks").isNull()) {
                    suggestedTasks = List.of();
                } else if (body.get("suggestedTasks").isArray()) {
                    suggestedTasks = MAPPER.convertValue(
                            body.get("suggestedTasks"), new TypeReference<List<Object>>() {});
                } else {
                    throw new IllegalArgumentException("suggestedTasks must be an array or null");
                }
            }
            ChatSessionStore.Message updated =
                    store.updateMessage(id, msgId, content, status, error, suggestedTasks);
            if (updated == null) {
                ctx.status(404).json(Map.of("error", "Session or message not found"));
                return;
            }
            scheduleSummary(id);
            if ("assistant".equals(updated.role) && "sent".equals(updated.status)
                    && !"sent".equals(previousStatus)) {
                scheduleMemoryExtraction(id, updated.id);
            }
            ctx.json(updated);
        } catch (Exception e) {
            if (writeClientError(ctx, e)) return;
            ctx.status(500).json(Map.of("error", "Failed to update message: " + e.getMessage()));
        }
    }

    /** PUT /desktop/chat/sessions/{id}/memory-policy */
    public void setMemoryPolicy(Context ctx) {
        try {
            String id = ctx.pathParam("id");
            if (!requireGeneratedSessionId(ctx, id)) return;
            JsonNode body = requireObject(MAPPER.readTree(DesktopChatJson.readBoundedBody(ctx)));
            requireTextFields(body, "memoryPolicy");
            String policy = textOrNull(body, "memoryPolicy");
            ChatSessionStore.Session updated = store.updateMemoryPolicy(id, policy);
            if (updated == null) {
                ctx.status(404).json(Map.of("error", "Session not found: " + id));
                return;
            }
            ctx.json(updated);
        } catch (Exception e) {
            if (writeClientError(ctx, e)) return;
            ctx.status(500).json(Map.of("error", "Failed to set memory policy: " + e.getMessage()));
        }
    }

    /** PUT /desktop/chat/active-session (SPEC-CSP-API-008). */
    public void setActiveSession(Context ctx) {
        try {
            JsonNode body = requireObject(MAPPER.readTree(DesktopChatJson.readBoundedBody(ctx)));
            requireTextFields(body, "activeSessionId");
            String activeId = textOrNull(body, "activeSessionId");
            if (activeId != null && !requireGeneratedSessionId(ctx, activeId)) return;
            if (activeId != null && store.getSession(activeId) == null) {
                ctx.status(400).json(Map.of("error", "Unknown session: " + activeId));
                return;
            }
            String persisted = store.setActiveSession(activeId);
            Map<String, Object> resp = new java.util.HashMap<>();
            resp.put("activeSessionId", persisted); // may be null
            ctx.json(resp);
        } catch (Exception e) {
            if (writeClientError(ctx, e)) return;
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
                if (session == null || session.messages == null) return;
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

    private String messageStatus(String sessionId, String messageId) {
        ChatSessionStore.Session session = store.getSession(sessionId);
        if (session == null || session.messages == null || messageId == null) return null;
        return session.messages.stream()
                .filter(message -> messageId.equals(message.id))
                .map(message -> message.status)
                .findFirst()
                .orElse(null);
    }

    // ── Parsing helpers ──────────────────────────────────────────

    /** Accept a single {@code Message} object or {@code {messages:[...]}} (SPEC-CSP-API-006). */
    private static List<ChatSessionStore.Message> parseMessages(String body) throws Exception {
        JsonNode node = MAPPER.readTree(body);
        if (node == null || node.isNull()) {
            throw new IllegalArgumentException("A chat message body is required");
        }
        if (node.isObject() && node.has("messages")) {
            if (!node.get("messages").isArray()) {
                throw new IllegalArgumentException("messages must be an array");
            }
            List<ChatSessionStore.Message> messages = MAPPER.convertValue(node.get("messages"),
                    new TypeReference<List<ChatSessionStore.Message>>() {});
            if (messages.isEmpty()) throw new IllegalArgumentException("messages must not be empty");
            return messages;
        }
        if (node.isArray()) {
            List<ChatSessionStore.Message> messages = MAPPER.convertValue(
                    node, new TypeReference<List<ChatSessionStore.Message>>() {});
            if (messages.isEmpty()) throw new IllegalArgumentException("messages must not be empty");
            return messages;
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException("Chat message body must be an object or array");
        }
        List<ChatSessionStore.Message> single = new ArrayList<>();
        single.add(MAPPER.convertValue(node, ChatSessionStore.Message.class));
        return single;
    }

    private static String textOrNull(JsonNode body, String field) {
        JsonNode n = body != null ? body.get(field) : null;
        if (n == null || n.isNull()) return null;
        if (!n.isTextual()) throw new IllegalArgumentException(field + " must be a string or null");
        return n.textValue();
    }

    private static JsonNode requireObject(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw new IllegalArgumentException("Chat request body must be a JSON object");
        }
        return body;
    }

    private static void requireTextFields(JsonNode body, String... fields) {
        for (String field : fields) {
            JsonNode value = body.get(field);
            if (value != null && !value.isNull() && !value.isTextual()) {
                throw new IllegalArgumentException(field + " must be a string or null");
            }
        }
    }

    private static void requireContainerOrNull(JsonNode body, String field) {
        JsonNode value = body.get(field);
        if (value != null && !value.isNull() && !value.isContainerNode()) {
            throw new IllegalArgumentException(field + " must be an object, array, or null");
        }
    }

    private static boolean writeClientError(Context ctx, Exception error) {
        if (error instanceof DesktopChatJson.PayloadTooLargeException) {
            ctx.status(413).json(Map.of("error", error.getMessage()));
            return true;
        }
        if (error instanceof HttpResponseException response) {
            ctx.status(response.getStatus()).json(Map.of(
                    "error", response.getMessage() == null ? "Invalid request" : response.getMessage()));
            return true;
        }
        if (error instanceof JsonProcessingException || error instanceof IllegalArgumentException) {
            ctx.status(400).json(Map.of(
                    "error", error.getMessage() == null ? "Invalid chat request" : error.getMessage()));
            return true;
        }
        return false;
    }

    private static boolean requireGeneratedSessionId(Context ctx, String id) {
        if (ChatSessionStore.isGeneratedSessionId(id)) return true;
        ctx.status(400).json(Map.of("error", "Invalid chat session id"));
        return false;
    }
}
