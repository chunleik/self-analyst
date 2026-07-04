package com.selfanalyst.memory;

import java.io.IOException;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class LongTermMemoryService {

    private final MemoryStore store;

    public LongTermMemoryService(MemoryStore store) {
        this.store = store;
    }

    public synchronized List<GrowthProfile.MemoryItem> list(String status, String type,
                                                            String sourceSessionId, String q) {
        String query = q != null ? q.trim().toLowerCase(Locale.ROOT) : "";
        return store.profile().getMemories().stream()
                .filter(m -> status == null || status.isBlank() || status.equals(m.status()))
                .filter(m -> type == null || type.isBlank() || type.equals(m.type()))
                .filter(m -> sourceSessionId == null || sourceSessionId.isBlank()
                        || sourceSessionId.equals(m.sourceSessionId()))
                .filter(m -> query.isEmpty()
                        || lower(m.content()).contains(query)
                        || lower(m.evidence()).contains(query))
                .sorted(Comparator.comparing(
                        GrowthProfile.MemoryItem::updatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .toList();
    }

    public synchronized GrowthProfile.MemoryItem createManual(String type, String content, String evidence,
                                                              String sourceSessionId, String source,
                                                              String requestedStatus) throws IOException {
        String clean = normalizeContent(content);
        rejectForbidden(clean);
        GrowthProfile.MemoryItem existing = findDuplicate(clean);
        if (existing != null) return existing;
        Instant now = Instant.now();
        GrowthProfile.MemoryItem item = new GrowthProfile.MemoryItem(
                newId(), normalizeType(type), clean, blankToNull(evidence), 10,
                normalizeStatus(requestedStatus), false, "confirm".equals(requestedStatus) ? "confirm" : "auto",
                source != null ? source : "ui_manual", sourceSessionId, List.of(), now, now);
        store.profile().getMemories().add(item);
        store.save();
        return item;
    }

    public synchronized GrowthProfile.MemoryItem update(String id, String type, String content, String evidence,
                                                        Integer confidence, String status, Boolean sensitive)
            throws IOException {
        List<GrowthProfile.MemoryItem> items = store.profile().getMemories();
        for (int i = 0; i < items.size(); i++) {
            GrowthProfile.MemoryItem old = items.get(i);
            if (old.id().equals(id)) {
                String nextContent = content != null ? normalizeContent(content) : old.content();
                rejectForbidden(nextContent);
                GrowthProfile.MemoryItem updated = new GrowthProfile.MemoryItem(
                        old.id(),
                        type != null ? normalizeType(type) : old.type(),
                        nextContent,
                        evidence != null ? blankToNull(evidence) : old.evidence(),
                        confidence != null ? clampConfidence(confidence) : old.confidence(),
                        status != null ? normalizeStatus(status) : old.status(),
                        sensitive != null ? sensitive : old.sensitive(),
                        old.approvalPolicy(),
                        old.source(),
                        old.sourceSessionId(),
                        old.sourceMessageIds(),
                        old.createdAt(),
                        Instant.now());
                items.set(i, updated);
                store.save();
                return updated;
            }
        }
        return null;
    }

    public synchronized boolean delete(String id) throws IOException {
        boolean removed = store.profile().getMemories().removeIf(m -> m.id().equals(id));
        if (removed) store.save();
        return removed;
    }

    private GrowthProfile.MemoryItem findDuplicate(String content) {
        String normalized = fingerprint(content);
        return store.profile().getMemories().stream()
                .filter(m -> !"rejected".equals(m.status()))
                .filter(m -> normalized.equals(fingerprint(m.content())))
                .findFirst()
                .orElse(null);
    }

    private static void rejectForbidden(String content) {
        String lower = content.toLowerCase(Locale.ROOT);
        if (lower.contains("api_key") || lower.contains("token=") || lower.contains("password=")
                || lower.matches(".*sk-[a-z0-9_-]{8,}.*")) {
            throw new IllegalArgumentException("Sensitive credential-like content cannot be stored as memory");
        }
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static String normalizeContent(String content) {
        if (content == null || content.trim().isEmpty()) {
            throw new IllegalArgumentException("Memory content is required");
        }
        return content.trim();
    }

    private static String normalizeType(String type) {
        String normalized = type == null ? "note" : type;
        return switch (normalized) {
            case "goal", "preference", "project", "pattern", "fact", "note" -> normalized;
            default -> "note";
        };
    }

    private static String normalizeStatus(String status) {
        String normalized = status == null ? "active" : status;
        return switch (normalized) {
            case "active", "pending", "disabled", "rejected" -> normalized;
            default -> "active";
        };
    }

    private static String fingerprint(String content) {
        return content == null ? "" : content.trim().replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static int clampConfidence(int value) {
        return Math.max(1, Math.min(10, value));
    }
}
