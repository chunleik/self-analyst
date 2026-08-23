package com.selfanalyst.desktop.controller;

import com.selfanalyst.agent.SelfAnalystAgent;
import com.selfanalyst.config.Config;
import com.selfanalyst.desktop.service.BehaviorAdviceService;
import com.selfanalyst.desktop.service.SummaryPromptService;
import com.selfanalyst.desktop.service.SummaryService;
import com.selfanalyst.desktop.store.ChatSessionStore;
import com.selfanalyst.desktop.store.TaskStore;
import com.selfanalyst.headroom.HeadroomService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    private final HeadroomService headroomService;
    private final ChatSessionStore chatSessionStore;

    public DesktopAgentController(SummaryService summaryService,
                                  BehaviorAdviceService adviceService,
                                   SelfAnalystAgent agent,
                                   TaskStore taskStore,
                                   Config config) {
        this(summaryService, adviceService, agent, taskStore, config, null, null);
    }

    public DesktopAgentController(SummaryService summaryService,
                                  BehaviorAdviceService adviceService,
                                  SelfAnalystAgent agent,
                                   TaskStore taskStore,
                                   Config config,
                                   HeadroomService headroomService) {
        this(summaryService, adviceService, agent, taskStore, config, headroomService, null);
    }

    public DesktopAgentController(SummaryService summaryService,
                                  BehaviorAdviceService adviceService,
                                  SelfAnalystAgent agent,
                                  TaskStore taskStore,
                                  Config config,
                                  HeadroomService headroomService,
                                  ChatSessionStore chatSessionStore) {
        this.summaryService = summaryService;
        this.adviceService = adviceService;
        this.promptService = new SummaryPromptService();
        this.agent = agent;
        this.taskStore = taskStore;
        this.config = config;
        this.headroomService = headroomService;
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
        if (headroomService != null) {
            payload.put("headroom", headroomService.snapshotWithFreshStats().toMap());
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
            Map<String, Object> body = MAPPER.readValue(ctx.body(), Map.class);
            String message = stringOr(body.get("message"), "");
            String sessionId = stringOr(body.get("sessionId"), "").trim();
            String userMessageId = stringOr(body.get("userMessageId"), "").trim();

            if (!sessionId.isEmpty()) {
                if (!ChatSessionStore.isGeneratedSessionId(sessionId)) {
                    ctx.status(400).json(Map.of("error", "Invalid chat session id"));
                    return;
                }
                if (chatSessionStore == null || chatSessionStore.getSession(sessionId) == null) {
                    ctx.status(404).json(Map.of("error", "Chat session not found"));
                    return;
                }
                if (!ChatSessionStore.isGeneratedMessageId(userMessageId)) {
                    ctx.status(400).json(Map.of("error", "Invalid user message id"));
                    return;
                }
                // Reject a valid-looking id that is not the server-owned user message for this
                // visible transcript. The same validation is repeated under the agent gate below.
                agentHistoryBeforeCurrentUser(
                        chatSessionStore.getSession(sessionId), userMessageId);
            }

            if (agent == null) {
                ctx.json(Map.of(
                        "message", "LLM 未配置，无法进行对话。请在配置页面设置 API Key。",
                        "suggestedTasks", List.of()
                ));
                return;
            }

            String agentInput = buildChatAgentInput(message, body.get("context"));
            String response = (sessionId.isEmpty()
                    ? agent.chat(agentInput)
                    : agent.chat(sessionId,
                            userMessageId,
                            agentInput,
                            () -> agentHistoryBeforeCurrentUser(
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
            if (hasCause(e, SelfAnalystAgent.ChatSessionUnavailableException.class)) {
                ctx.status(404).json(Map.of("error", "Chat session not found"));
            } else if (hasCause(e, InvalidChatTurnException.class)) {
                ctx.status(400).json(Map.of("error", "User message does not belong to session"));
            } else if (hasCause(e, SelfAnalystAgent.StaleChatTurnException.class)) {
                ctx.status(409).json(Map.of(
                        "error", "Only the latest incomplete user turn can be resumed"));
            } else if (hasAgentStillRunning(e)) {
                // A rejected turn was never added to AgentState. Report a conflict so the
                // frontend keeps the same userMessageId on its retry path.
                ctx.status(409).json(Map.of(
                        "error", "上一条消息仍在处理中，请稍后再试..."));
            } else {
                log.error("Chat request failed", e);
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                ctx.status(500).json(Map.of("error", "Chat failed: " + msg));
            }
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
                throw new InvalidChatTurnException();
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
            }
        }
        return tasks;
    }

    private static String stringOr(Object val, String fallback) {
        return val != null ? val.toString() : fallback;
    }
}
