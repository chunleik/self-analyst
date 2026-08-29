package com.selfanalyst.desktop.controller;

import com.selfanalyst.agent.SelfAnalystAgent;
import com.selfanalyst.config.Config;
import com.selfanalyst.desktop.service.BehaviorAdviceService;
import com.selfanalyst.desktop.service.SummaryPromptService;
import com.selfanalyst.desktop.service.SummaryService;
import com.selfanalyst.desktop.store.ChatSessionStore;
import com.selfanalyst.desktop.store.TaskStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.javalin.http.Context;
import io.javalin.http.HttpResponseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Agent tab endpoints: current summary + chat.
 *
 * <pre>
 *   GET  /desktop/summary  → { current: {...}, timeline: [...] }
 *   POST /desktop/chat     → { message, context } → { message, suggestedTasks: [...] }
 * </pre>
 */
public class DesktopAgentController {

    private static final Logger log = LoggerFactory.getLogger(DesktopAgentController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ExecutorService LLM_POOL = Executors.newFixedThreadPool(
            4, r -> { Thread t = new Thread(r, "llm-enhance"); t.setDaemon(true); return t; });

    private final SummaryService summaryService;
    private final BehaviorAdviceService adviceService;
    private final SummaryPromptService promptService;
    private final SelfAnalystAgent agent;
    private final TaskStore taskStore;
    private final Config config;
    private final ChatSessionStore chatSessionStore;

    public DesktopAgentController(SummaryService summaryService,
                                  BehaviorAdviceService adviceService,
                                   SelfAnalystAgent agent,
                                   TaskStore taskStore,
                                   Config config) {
        this(summaryService, adviceService, agent, taskStore, config, null);
    }

    public DesktopAgentController(SummaryService summaryService,
                                  BehaviorAdviceService adviceService,
                                  SelfAnalystAgent agent,
                                  TaskStore taskStore,
                                  Config config,
                                  ChatSessionStore chatSessionStore) {
        this.summaryService = summaryService;
        this.adviceService = adviceService;
        this.promptService = new SummaryPromptService();
        this.agent = agent;
        this.taskStore = taskStore;
        this.config = config;
        this.chatSessionStore = chatSessionStore;
    }

    /**
     * GET /desktop/summary
     * Returns current status card and timeline entries.
     */
    public void getSummary(Context ctx) {
        try {
            SummaryService.LocalFacts current = summaryService.getCurrentStatus();
            List<SummaryService.TimelineEntry> timeline = summaryService.getTimeline();

            // Only enhance with LLM if agent is available AND has a valid API key
            SummaryPromptService.EnhancedSummary enhanced;
            boolean llmAvailable = agent != null && config != null
                    && config.llmApiKey() != null && !config.llmApiKey().isBlank()
                    && !config.llmApiKey().contains("CHANGE_ME")
                    && !agent.isBudgetBlocked();
            SummaryPromptService.SummaryTextClient summaryClient =
                    llmAvailable ? agent::completePlain : null;
            com.selfanalyst.i18n.Lang lang = config != null
                    ? config.effectiveLanguage() : com.selfanalyst.i18n.Lang.ZH;
            if (llmAvailable) {
                enhanced = promptService.enhance(current, summaryClient, lang);
            } else {
                enhanced = promptService.localOnly(current);
            }

            Map<String, Object> currentMap = new LinkedHashMap<>();
            currentMap.put("headline", enhanced.headline());
            currentMap.put("insight", enhanced.insight());
            currentMap.put("suggestion", enhanced.suggestion());
            currentMap.put("confidence", enhanced.confidence());
            currentMap.put("evidence", enhanced.evidence());
            currentMap.put("topApps", enhanced.topApps());
            currentMap.put("activeTime", enhanced.activeTime());
            currentMap.put("afkTime", enhanced.afkTime());
            currentMap.put("switchCount", enhanced.switchCount());
            currentMap.put("goalContext", enhanced.goalContext());

            // If LLM enhancement failed for current status, skip it for timeline entries
            boolean llmFailed = enhanced.insight() != null
                    && enhanced.insight().equals(SummaryPromptService.noInsightText(lang));
            // Cap how many timeline entries get LLM enhancement to bound per-page token cost
            // (the rest fall back to local-only). Beyond cap → no LLM call.
            int llmCap = config != null ? config.desktopSummaryMaxTimelineLlm() : 0;
            // Run timeline calls concurrently; only the first llmCap entries hit the LLM.
            List<CompletableFuture<Map<String, Object>>> timelineFutures = new ArrayList<>();
            for (int i = 0; i < timeline.size(); i++) {
                final SummaryService.TimelineEntry te = timeline.get(i);
                final boolean useLlm = !llmFailed && llmAvailable && i < llmCap;
                timelineFutures.add(CompletableFuture.supplyAsync(() -> {
                    var e = useLlm ? promptService.enhance(te.facts(), summaryClient, lang)
                                   : promptService.localOnly(te.facts());
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("key", te.key());
                    m.put("label", te.label());
                    m.put("headline", e.headline());
                    m.put("insight", e.insight());
                    m.put("confidence", e.confidence());
                    m.put("topApps", e.topApps());
                    m.put("activeTime", e.activeTime());
                    m.put("afkTime", e.afkTime());
                    m.put("switchCount", e.switchCount());
                    m.put("evidence", e.evidence());
                    return m;
                }, LLM_POOL));
            }
            List<Map<String, Object>> timelineList = timelineFutures.stream()
                    .map(CompletableFuture::join)
                    .toList();

            // Generate behavior advice (SPEC-ADV-API-001)
            Map<String, Object> adviceMap;
            try {
                SummaryService.BehaviorData behaviorData = summaryService.getBehaviorData();
                BehaviorAdviceService.BehaviorAdvice rawAdvice = adviceService.generate(behaviorData);

                BehaviorAdviceService.BehaviorAdvice finalAdvice;
                if (!"empty".equals(rawAdvice.type()) && llmAvailable && !llmFailed) {
                    finalAdvice = promptService.enhanceAdvice(rawAdvice, summaryClient, lang);
                } else {
                    finalAdvice = rawAdvice;
                }

                adviceMap = new LinkedHashMap<>();
                adviceMap.put("type", finalAdvice.type());
                adviceMap.put("scopeLabel", finalAdvice.scopeLabel());
                adviceMap.put("generatedAt", finalAdvice.generatedAt());
                adviceMap.put("title", finalAdvice.title());
                adviceMap.put("body", finalAdvice.body());
                adviceMap.put("evidenceTags", finalAdvice.evidenceTags());
                adviceMap.put("confidence", finalAdvice.confidence());
                adviceMap.put("emptyReason", finalAdvice.emptyReason());

                Map<String, Object> basisMap = new LinkedHashMap<>();
                if (finalAdvice.basis() != null) {
                    basisMap.put("observationRange", finalAdvice.basis().observationRange());
                    basisMap.put("trend", finalAdvice.basis().trend());
                    basisMap.put("adviceKind", finalAdvice.basis().adviceKind());
                    basisMap.put("dataCompleteness", finalAdvice.basis().dataCompleteness());
                }
                adviceMap.put("basis", basisMap);
            } catch (Exception e) {
                // SPEC-ADV-API-003: advice failure must not fail the whole summary
                adviceMap = new LinkedHashMap<>();
                adviceMap.put("type", "empty");
                adviceMap.put("scopeLabel", "数据不足");
                adviceMap.put("generatedAt", java.time.Instant.now().toString());
                adviceMap.put("title", "暂时无法生成行为建议");
                adviceMap.put("body", "");
                adviceMap.put("evidenceTags", List.of());
                adviceMap.put("confidence", "low");
                adviceMap.put("emptyReason", "生成失败");
                adviceMap.put("basis", Map.of("observationRange", "—", "trend", "—", "adviceKind", "—", "dataCompleteness", "低"));
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("behaviorAdvice", adviceMap);
            result.put("current", currentMap);
            result.put("timeline", timelineList);
            ctx.json(result);
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", "Failed to generate summary: " + e.getMessage()));
        }
    }

    /**
     * GET /desktop/usage
     * Returns today's LLM token usage and budget status.
     */
    public void getUsage(Context ctx) {
        ctx.json(usagePayload());
    }

    Map<String, Object> usagePayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (agent == null) {
            payload.put("mode", "off");
            payload.put("status", "ok");
        } else {
            payload.putAll(agent.usageSnapshot());
        }
        return payload;
    }

    /**
     * POST /desktop/chat
     * Body: { "message": "...", "context": {...}, "sessionId": "<hex32>",
     *         "userMessageId": "<hex12>" }
     * sessionId is optional only for the legacy drawer/client path.
     */
    public void chat(Context ctx) {
        try {
            ChatRequest request = parseChatRequest(ctx);
            if (!validateSessionRouting(ctx, request)) return;
            String sessionId = request.sessionId();
            String userMessageId = request.userMessageId();

            if (agent == null) {
                ctx.json(Map.of(
                        "message", "LLM 未配置，无法进行对话。请在配置页面设置 API Key。",
                        "suggestedTasks", List.of()
                ));
                return;
            }

            String response = (sessionId.isEmpty()
                    ? agent.chat(buildChatAgentInput(request.message(), request.context()))
                    : agent.chat(sessionId,
                            userMessageId,
                            () -> persistedTurnFromSession(
                                    chatSessionStore.getSession(sessionId), userMessageId)))
                    .block(Duration.ofSeconds(180));
            if (response == null || response.isBlank()) {
                ctx.json(Map.of(
                        "message", "Agent 暂时无响应",
                        "suggestedTasks", List.of()
                ));
                return;
            }

            // Extract suggested tasks from the response (heuristic)
            List<Map<String, Object>> suggestedTasks = extractSuggestedTasks(response);

            ctx.json(Map.of(
                    "message", response,
                    "suggestedTasks", suggestedTasks
            ));
        } catch (Exception e) {
            writeChatErrorResponse(ctx, e);
        }
    }

    /** POST /desktop/chat/stream — SSE deltas followed by one canonical result event. */
    public void chatStream(Context ctx) {
        OutputStream output = null;
        try {
            ChatRequest request = parseChatRequest(ctx);
            if (request.sessionId().isEmpty()) {
                throw new IllegalArgumentException(
                        "sessionId and userMessageId are required for streaming chat");
            }
            if (!validateSessionRouting(ctx, request)) return;
            if (agent == null) {
                ctx.status(503).json(Map.of(
                        "error", "LLM 未配置，无法进行对话。请在配置页面设置 API Key。"));
                return;
            }

            ctx.contentType("text/event-stream; charset=utf-8");
            ctx.header("Cache-Control", "no-cache, no-store, must-revalidate");
            ctx.header("X-Accel-Buffering", "no");
            ctx.header("X-Content-Type-Options", "nosniff");
            output = ctx.res().getOutputStream();
            OutputStream streamOutput = output;
            AtomicBoolean resultSent = new AtomicBoolean();

            agent.chatStream(request.sessionId(), request.userMessageId(),
                            () -> persistedTurnFromSession(
                                    chatSessionStore.getSession(request.sessionId()),
                                    request.userMessageId()))
                    .doOnNext(event -> {
                        try {
                            if (event.type() == SelfAnalystAgent.ChatStreamEventType.DELTA) {
                                writeSseEvent(streamOutput, "delta", Map.of("text", event.text()));
                            } else if (event.type()
                                    == SelfAnalystAgent.ChatStreamEventType.RESULT) {
                                String response = event.text() == null || event.text().isBlank()
                                        ? "Agent 暂时无响应" : event.text();
                                Map<String, Object> result = new LinkedHashMap<>();
                                result.put("message", response);
                                result.put("suggestedTasks", extractSuggestedTasks(response));
                                writeSseEvent(streamOutput, "result", result);
                                resultSent.set(true);
                            }
                        } catch (IOException disconnected) {
                            throw new SseWriteException(disconnected);
                        }
                    })
                    .blockLast(Duration.ofSeconds(180));

            if (!resultSent.get()) {
                writeSseEvent(output, "result", Map.of(
                        "message", "Agent 暂时无响应", "suggestedTasks", List.of()));
            }
        } catch (Exception e) {
            if (hasCause(e, SseWriteException.class)) {
                log.debug("Chat stream client disconnected");
                return;
            }
            ChatError error = describeChatError(e);
            if (output != null && ctx.res().isCommitted()) {
                try {
                    writeSseEvent(output, "error", Map.of(
                            "status", error.status(), "error", error.message()));
                } catch (IOException disconnected) {
                    log.debug("Chat stream client disconnected while reporting an error");
                }
            } else {
                writeChatErrorResponse(ctx, error);
            }
        }
    }

    /** POST /desktop/chat/sessions/{id}/cancel — best-effort interruption for a live stream. */
    public void cancelChat(Context ctx) {
        try {
            String sessionId = ctx.pathParam("id");
            if (!ChatSessionStore.isGeneratedSessionId(sessionId)) {
                ctx.status(400).json(Map.of("error", "Invalid chat session id"));
                return;
            }
            if (chatSessionStore == null || chatSessionStore.getSession(sessionId) == null) {
                ctx.status(404).json(Map.of("error", "Chat session not found"));
                return;
            }
            if (agent == null) {
                ctx.status(503).json(Map.of("error", "LLM is not configured"));
                return;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> body = DesktopChatJson.MAPPER.readValue(
                    DesktopChatJson.readBoundedBody(ctx), Map.class);
            Object rawMessageId = body == null ? null : body.get("userMessageId");
            if (!(rawMessageId instanceof String userMessageId)
                    || !ChatSessionStore.isGeneratedMessageId(userMessageId)) {
                ctx.status(400).json(Map.of("error", "Invalid user message id"));
                return;
            }
            boolean requested = agent.cancelChat(sessionId, userMessageId);
            ctx.status(requested ? 202 : 200).json(Map.of(
                    "cancelRequested", requested,
                    "sessionId", sessionId,
                    "userMessageId", userMessageId));
        } catch (Exception e) {
            writeChatErrorResponse(ctx, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static ChatRequest parseChatRequest(Context ctx) throws IOException {
        Map<String, Object> body = DesktopChatJson.MAPPER.readValue(
                DesktopChatJson.readBoundedBody(ctx), Map.class);
        if (body == null) throw new IllegalArgumentException("Chat request body is required");
        if (body.get("message") != null && !(body.get("message") instanceof String)) {
            throw new IllegalArgumentException("message must be a string");
        }
        for (String idField : List.of("sessionId", "userMessageId")) {
            if (body.get(idField) != null && !(body.get(idField) instanceof String)) {
                throw new IllegalArgumentException(idField + " must be a string");
            }
        }
        Object requestContext = body.get("context");
        if (requestContext != null && !(requestContext instanceof Map<?, ?>)
                && !(requestContext instanceof List<?>)) {
            throw new IllegalArgumentException("context must be an object, array, or null");
        }
        String sessionId = stringOr(body.get("sessionId"), "").trim();
        String userMessageId = stringOr(body.get("userMessageId"), "").trim();
        if (sessionId.isEmpty() != userMessageId.isEmpty()) {
            throw new IllegalArgumentException(
                    "sessionId and userMessageId must be provided together");
        }
        return new ChatRequest(
                stringOr(body.get("message"), ""), requestContext, sessionId, userMessageId);
    }

    private boolean validateSessionRouting(Context ctx, ChatRequest request) {
        if (request.sessionId().isEmpty()) return true;
        if (!ChatSessionStore.isGeneratedSessionId(request.sessionId())) {
            ctx.status(400).json(Map.of("error", "Invalid chat session id"));
            return false;
        }
        if (chatSessionStore == null || chatSessionStore.getSession(request.sessionId()) == null) {
            ctx.status(404).json(Map.of("error", "Chat session not found"));
            return false;
        }
        if (!ChatSessionStore.isGeneratedMessageId(request.userMessageId())) {
            ctx.status(400).json(Map.of("error", "Invalid user message id"));
            return false;
        }
        // Validate once before committing response headers. The same check is repeated under the
        // application-wide agent gate so a concurrent deletion cannot race the stream.
        persistedTurnFromSession(
                chatSessionStore.getSession(request.sessionId()), request.userMessageId());
        return true;
    }

    static void writeSseEvent(OutputStream output, String event, Object payload)
            throws IOException {
        String frame = "event: " + event + "\n"
                + "data: " + MAPPER.writeValueAsString(payload) + "\n\n";
        output.write(frame.getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private static void writeChatErrorResponse(Context ctx, Exception failure) {
        writeChatErrorResponse(ctx, describeChatError(failure));
    }

    private static void writeChatErrorResponse(Context ctx, ChatError error) {
        ctx.status(error.status()).json(Map.of("error", error.message()));
    }

    private static ChatError describeChatError(Exception e) {
        if (e instanceof DesktopChatJson.PayloadTooLargeException) {
            return new ChatError(413, e.getMessage());
        }
        if (e instanceof HttpResponseException responseException) {
            return new ChatError(responseException.getStatus(),
                    responseException.getMessage() == null
                            ? "Invalid request" : responseException.getMessage());
        }
        if (e instanceof JsonProcessingException) {
            return new ChatError(400, "Invalid chat JSON: " + e.getMessage());
        }
        if (hasCause(e, SelfAnalystAgent.ChatSessionUnavailableException.class)) {
            return new ChatError(404, "Chat session not found");
        }
        if (hasCause(e, InvalidChatTurnException.class)) {
            return new ChatError(400, "User message does not belong to session");
        }
        if (hasCause(e, SelfAnalystAgent.StaleChatTurnException.class)) {
            return new ChatError(409, "Only the latest incomplete user turn can be resumed");
        }
        if (hasCause(e, SelfAnalystAgent.ChatCancelledException.class)) {
            return new ChatError(409, "Chat request was cancelled");
        }
        if (hasAgentStillRunning(e)) {
            return new ChatError(409, "上一条消息仍在处理中，请稍后再试...");
        }
        if (e instanceof IllegalArgumentException) {
            return new ChatError(400,
                    e.getMessage() == null ? "Invalid chat request" : e.getMessage());
        }
        log.error("Chat request failed", e);
        String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        return new ChatError(500, "Chat failed: " + message);
    }

    private record ChatRequest(
            String message, Object context, String sessionId, String userMessageId) {}

    private record ChatError(int status, String message) {}

    private static final class SseWriteException extends UncheckedIOException {
        private SseWriteException(IOException cause) {
            super(cause);
        }
    }

    static boolean hasAgentStillRunning(Throwable throwable) {
        Throwable cur = throwable;
        while (cur != null) {
            String msg = cur.getMessage();
            if (msg != null && msg.toLowerCase(Locale.ROOT).contains("still running")) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    private static boolean hasCause(Throwable throwable, Class<? extends Throwable> type) {
        Throwable current = throwable;
        while (current != null) {
            if (type.isInstance(current)) return true;
            current = current.getCause();
        }
        return false;
    }

    /**
     * Converts the visible transcript before the current server-owned user message into a one-time
     * AgentState bootstrap. Pending/error assistant projections and UI-only system notices are not
     * model history. A null session is the under-gate deletion signal.
     */
    static List<Msg> agentHistoryBeforeCurrentUser(
            ChatSessionStore.Session session, String currentUserMessageId) {
        if (session == null) return null;
        if (!ChatSessionStore.isGeneratedMessageId(currentUserMessageId)) {
            throw new InvalidChatTurnException();
        }
        List<ChatSessionStore.Message> transcript = session.messages != null
                ? session.messages : List.of();
        int currentIndex = -1;
        for (int i = 0; i < transcript.size(); i++) {
            ChatSessionStore.Message message = transcript.get(i);
            if (message != null && currentUserMessageId.equals(message.id)
                    && "user".equals(message.role)) {
                currentIndex = i;
                break;
            }
        }
        if (currentIndex < 0) throw new InvalidChatTurnException();
        for (int i = currentIndex + 1; i < transcript.size(); i++) {
            ChatSessionStore.Message later = transcript.get(i);
            if (later != null && "user".equals(later.role)) {
                throw new SelfAnalystAgent.StaleChatTurnException(currentUserMessageId);
            }
        }

        List<Msg> history = new ArrayList<>();
        for (int i = 0; i < currentIndex; i++) {
            ChatSessionStore.Message visible = transcript.get(i);
            if (visible == null || visible.content == null || visible.content.isBlank()) continue;
            MsgRole role;
            if ("user".equals(visible.role)) {
                role = MsgRole.USER;
            } else if ("assistant".equals(visible.role)) {
                if ("pending".equals(visible.status) || "error".equals(visible.status)) continue;
                role = MsgRole.ASSISTANT;
            } else {
                continue;
            }
            Msg.Builder builder = Msg.builder()
                    .name(visible.role)
                    .role(role)
                    .textContent(visible.content);
            if (ChatSessionStore.isGeneratedMessageId(visible.id)) builder.id(visible.id);
            history.add(builder.build());
        }
        return List.copyOf(history);
    }

    static SelfAnalystAgent.PersistedDesktopTurn persistedTurnFromSession(
            ChatSessionStore.Session session, String currentUserMessageId) {
        if (session == null) return null;
        List<Msg> history = agentHistoryBeforeCurrentUser(session, currentUserMessageId);
        ChatSessionStore.Message current = session.messages.stream()
                .filter(message -> message != null
                        && currentUserMessageId.equals(message.id)
                        && "user".equals(message.role))
                .findFirst()
                .orElseThrow(InvalidChatTurnException::new);
        return new SelfAnalystAgent.PersistedDesktopTurn(
                current.content != null ? current.content : "",
                current.contextSnapshot,
                history);
    }

    private static final class InvalidChatTurnException extends IllegalArgumentException {
    }

    static String buildChatAgentInput(String message, Object context) {
        if (context == null) return message;
        String contextJson;
        try {
            contextJson = MAPPER.writeValueAsString(context);
        } catch (Exception e) {
            contextJson = String.valueOf(context);
        }
        if (contextJson == null || contextJson.isBlank() || "{}".equals(contextJson)) {
            return message;
        }
        return """
                当前桌面会话上下文（仅供参考，不要原样输出）：
                %s

                用户消息：
                %s

                回答要求：
                - 用自然中文直接回答用户。
                - 除非用户明确要求 JSON，否则不要输出 JSON、代码块或原始数据对象。
                - 如果上下文不足，说明还需要哪些信息。
                """.formatted(contextJson, message);
    }

    /**
     * Scans agent response for lines that look like task suggestions
     * (e.g. "建议: ..." or "- [ ] ...") and converts them to task stubs.
     */
    private List<Map<String, Object>> extractSuggestedTasks(String response) {
        List<Map<String, Object>> tasks = new ArrayList<>();
        for (String line : response.lines().toList()) {
            String trimmed = line.trim();
            // Match patterns like "- 建议: ..." or "- [ ] ..." or "建议: ..."
            String content = null;
            if (trimmed.matches("^[-*]\\s*\\[\\s*\\]\\s+.+")) {
                content = trimmed.replaceFirst("^[-*]\\s*\\[\\s*\\]\\s*", "");
            } else if (trimmed.startsWith("建议：") || trimmed.startsWith("建议:")) {
                content = trimmed.replaceFirst("^建议[：:]\\s*", "");
            } else if (trimmed.startsWith("- 建议：") || trimmed.startsWith("- 建议:")) {
                content = trimmed.replaceFirst("^[-*]\\s*建议[：:]\\s*", "");
            }
            if (content != null && !content.isBlank() && content.length() < 200) {
                Map<String, Object> task = new LinkedHashMap<>();
                task.put("title", content);
                task.put("source", "agent_suggestion");
                tasks.add(task);
                if (tasks.size() >= 20) break;
            }
        }
        return tasks;
    }

    private static String stringOr(Object val, String fallback) {
        return val != null ? val.toString() : fallback;
    }
}
