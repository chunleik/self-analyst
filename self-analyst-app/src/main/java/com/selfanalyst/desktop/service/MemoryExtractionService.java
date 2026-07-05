package com.selfanalyst.desktop.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfanalyst.desktop.store.ChatSessionStore;
import com.selfanalyst.i18n.Lang;
import com.selfanalyst.memory.LongTermMemoryService;

import java.time.Duration;
import java.util.List;

public class MemoryExtractionService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration EXTRACTION_TIMEOUT = Duration.ofSeconds(20);
    private static final int MAX_PROMPT_FIELD = 4000;

    private final LongTermMemoryService memoryService;
    private final Lang lang;

    public MemoryExtractionService(LongTermMemoryService memoryService, Lang lang) {
        this.memoryService = memoryService;
        this.lang = lang;
    }

    public void extractAfterAssistantSent(ChatSessionStore.Session session,
                                          ChatSessionStore.Message user,
                                          ChatSessionStore.Message assistant,
                                          SummaryPromptService.SummaryTextClient client) {
        if (session == null || "off".equals(session.memoryPolicy) || client == null) return;
        try {
            String raw = client.complete(buildPrompt(session, user, assistant), EXTRACTION_TIMEOUT);
            for (Candidate candidate : parseCandidates(raw)) {
                saveCandidate(session, user, assistant, candidate);
            }
        } catch (Exception ignored) {
            // Extraction is best-effort and must never affect the chat response path.
        }
    }

    private String buildPrompt(ChatSessionStore.Session session, ChatSessionStore.Message user,
                               ChatSessionStore.Message assistant) {
        String language = lang == Lang.EN ? "English" : "中文";
        return """
                你是 SelfAnalyst 的长期记忆提炼器。请只输出 JSON 数组。
                只提炼未来多次对话有用的长期信息。不要保存密码、token、API key、验证码或大段原文。
                自动保存仅限用户明确陈述的低风险偏好、目标、长期项目事实。
                行为模式、敏感内容、推断、低置信度内容必须 approvalPolicy=confirm。
                输出语言: %s
                会话标题: %s
                用户消息: %s
                助手回复: %s
                字段: type, content, evidence, confidence(1-10), sensitive, approvalPolicy(auto|confirm)
                """.formatted(language, safe(session.title), safe(user != null ? user.content : null),
                safe(assistant != null ? assistant.content : null));
    }

    private List<Candidate> parseCandidates(String raw) throws Exception {
        if (raw == null || raw.isBlank()) return List.of();
        String text = raw.trim();
        if (text.startsWith("```")) {
            text = text.replaceFirst("^```(?:json)?\\s*", "")
                    .replaceFirst("\\s*```$", "")
                    .trim();
        }
        return MAPPER.readValue(text, new TypeReference<List<Candidate>>() {});
    }

    private void saveCandidate(ChatSessionStore.Session session, ChatSessionStore.Message user,
                               ChatSessionStore.Message assistant, Candidate candidate)
            throws Exception {
        if (candidate == null || candidate.content() == null || candidate.content().isBlank()) return;
        String status = shouldAutoActivate(session, candidate) ? "active" : "pending";
        memoryService.createExtracted(
                candidate.type(), candidate.content(), candidate.evidence(), clamp(candidate.confidence()),
                candidate.sensitive(), candidate.approvalPolicy(), status, session.id,
                messageIds(user, assistant));
    }

    private static boolean shouldAutoActivate(ChatSessionStore.Session session, Candidate candidate) {
        if ("confirm_all".equals(session.memoryPolicy)) return false;
        if (candidate.sensitive()) return false;
        if (candidate.confidence() < 8) return false;
        return "auto".equals(candidate.approvalPolicy());
    }

    private static List<String> messageIds(ChatSessionStore.Message user, ChatSessionStore.Message assistant) {
        String userId = user != null ? user.id : null;
        String assistantId = assistant != null ? assistant.id : null;
        if (userId == null || userId.isBlank()) {
            return assistantId == null || assistantId.isBlank() ? List.of() : List.of(assistantId);
        }
        if (assistantId == null || assistantId.isBlank()) {
            return List.of(userId);
        }
        return List.of(userId, assistantId);
    }

    private static int clamp(int value) {
        return Math.max(1, Math.min(10, value));
    }

    private static String safe(String value) {
        if (value == null) return "";
        return value.length() > MAX_PROMPT_FIELD ? value.substring(0, MAX_PROMPT_FIELD) : value;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Candidate(String type, String content, String evidence, int confidence,
                     boolean sensitive, String approvalPolicy) {
    }
}
