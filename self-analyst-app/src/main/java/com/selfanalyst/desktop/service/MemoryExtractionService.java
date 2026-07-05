package com.selfanalyst.desktop.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfanalyst.desktop.store.ChatSessionStore;
import com.selfanalyst.i18n.Lang;
import com.selfanalyst.memory.GrowthProfile;
import com.selfanalyst.memory.LongTermMemoryService;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

public class MemoryExtractionService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration EXTRACTION_TIMEOUT = Duration.ofSeconds(20);
    private static final int MAX_PROMPT_FIELD = 4000;
    private static final int MAX_MEMORY_SNAPSHOT = 2000;

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
        if (session == null) return;
        try {
            String forgetTarget = forgetTarget(user != null ? user.content : null);
            if (forgetTarget != null) {
                memoryService.disableMatching(forgetTarget);
                return;
            }
            if ("off".equals(session.memoryPolicy) || client == null) return;
            if (memoryService.hasSourceMessageId(assistant != null ? assistant.id : null)) return;
            if (containsForbiddenChatText(user, assistant)) return;
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
                当前会话ID: %s
                会话标题: %s
                记忆策略: %s
                相关消息ID: user=%s, assistant=%s
                现有 active/pending 记忆摘要: %s
                用户消息: %s
                助手回复: %s
                字段: type, content, evidence, confidence(1-10), sensitive, approvalPolicy(auto|confirm)
                """.formatted(language, safe(session.id), safe(session.title), safe(session.memoryPolicy),
                safe(user != null ? user.id : null), safe(assistant != null ? assistant.id : null),
                safe(memorySnapshot()), safe(user != null ? user.content : null),
                safe(assistant != null ? assistant.content : null));
    }

    private String memorySnapshot() {
        String snapshot = memoryService.list(null, null, null, null).stream()
                .filter(m -> "active".equals(m.status()) || "pending".equals(m.status()))
                .filter(m -> !LongTermMemoryService.containsForbiddenContent(m.content()))
                .filter(m -> !LongTermMemoryService.containsForbiddenContent(m.evidence()))
                .limit(12)
                .map(MemoryExtractionService::formatMemory)
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
        return snapshot.isBlank() ? "无" : safe(snapshot, MAX_MEMORY_SNAPSHOT);
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

    private static boolean containsForbiddenChatText(ChatSessionStore.Message user,
                                                     ChatSessionStore.Message assistant) {
        return LongTermMemoryService.containsForbiddenContent(user != null ? user.content : null)
                || LongTermMemoryService.containsForbiddenContent(assistant != null ? assistant.content : null);
    }

    private static int clamp(int value) {
        return Math.max(1, Math.min(10, value));
    }

    private static String formatMemory(GrowthProfile.MemoryItem item) {
        return "- [%s/%s] %s%s".formatted(
                safe(item.status()), safe(item.type()), safe(item.content()),
                item.evidence() == null || item.evidence().isBlank() ? "" : " (" + safe(item.evidence()) + ")");
    }

    private static String forgetTarget(String content) {
        if (content == null || content.isBlank()) return null;
        String lower = content.toLowerCase(Locale.ROOT);
        String[] markers = {
                "不要记住", "别记住", "不用记住", "不要保存", "忘记",
                "do not remember", "don't remember", "forget"
        };
        for (String marker : markers) {
            int index = lower.indexOf(marker);
            if (index < 0) continue;
            String target = content.substring(index + marker.length())
                    .replaceFirst("^[\\s:：,，。.!！?？]+", "")
                    .trim();
            return target.isBlank() ? content.trim() : target;
        }
        return null;
    }

    private static String safe(String value) {
        return safe(value, MAX_PROMPT_FIELD);
    }

    private static String safe(String value, int maxLength) {
        if (value == null) return "";
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Candidate(String type, String content, String evidence, int confidence,
                     boolean sensitive, String approvalPolicy) {
    }
}
