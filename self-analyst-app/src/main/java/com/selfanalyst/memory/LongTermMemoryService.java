package com.selfanalyst.memory;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public class LongTermMemoryService {

    private static final Pattern CREDENTIAL_LIKE_PATTERN = Pattern.compile(
            "(?is).*(\\b[a-z0-9_-]*api[_-]?key\\b\\s*[:=]\\s*\\S+"
                    + "|\\b[a-z0-9_-]*token\\b\\s*[:=]\\s*\\S+"
                    + "|\\bpassword\\b\\s*[:=]\\s*\\S+"
                    + "|\\bauthorization\\b\\s*:\\s*bearer\\s+\\S+"
                    + "|\\bsk-[a-z0-9_-]{8,}\\b).*");

    private final MemoryStore store;

    public LongTermMemoryService(MemoryStore store) {
        this.store = store;
    }

    public synchronized GrowthProfile profile() {
        return store.profile();
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
                .sorted(LongTermMemoryService::compareUpdatedAtDescendingNullsLast)
                .toList();
    }

    public synchronized GrowthProfile.MemoryItem createManual(String type, String content, String evidence,
                                                              String sourceSessionId, String source,
                                                              String requestedStatus) throws IOException {
        String clean = normalizeContent(content);
        String cleanEvidence = blankToNull(evidence);
        rejectForbidden(clean);
        rejectForbidden(cleanEvidence);
        GrowthProfile.MemoryItem existing = findDuplicate(clean);
        if (existing != null) return existing;
        Instant now = Instant.now();
        String cleanStatus = normalizeStatus(requestedStatus);
        GrowthProfile.MemoryItem item = new GrowthProfile.MemoryItem(
                newId(), normalizeType(type), clean, cleanEvidence, 10,
                cleanStatus, false, "confirm".equals(canonical(requestedStatus)) ? "confirm" : "auto",
                normalizeSource(source), sourceSessionId, List.of(), now, now);
        List<GrowthProfile.MemoryItem> items = store.profile().getMemories();
        List<GrowthProfile.MemoryItem> snapshot = new ArrayList<>(items);
        items.add(item);
        saveOrRestore(items, snapshot);
        return item;
    }

    public synchronized GrowthProfile.MemoryItem createExtracted(String type, String content, String evidence,
                                                                 int confidence, boolean sensitive,
                                                                 String approvalPolicy, String status,
                                                                 String sourceSessionId,
                                                                 List<String> sourceMessageIds)
            throws IOException {
        String clean = normalizeContent(content);
        String cleanEvidence = blankToNull(evidence);
        rejectForbidden(clean);
        rejectForbidden(cleanEvidence);
        GrowthProfile.MemoryItem existing = findDuplicate(clean);
        if (existing != null) return existing;
        Instant now = Instant.now();
        GrowthProfile.MemoryItem item = new GrowthProfile.MemoryItem(
                newId(), normalizeType(type), clean, cleanEvidence, clampConfidence(confidence),
                normalizeStatus(status), sensitive, normalizeApprovalPolicy(approvalPolicy),
                "chat_auto", sourceSessionId, normalizeMessageIds(sourceMessageIds), now, now);
        List<GrowthProfile.MemoryItem> items = store.profile().getMemories();
        List<GrowthProfile.MemoryItem> snapshot = new ArrayList<>(items);
        items.add(item);
        saveOrRestore(items, snapshot);
        return item;
    }

    public synchronized GrowthProfile.MemoryItem update(String id, String type, String content, String evidence,
                                                        Integer confidence, String status, Boolean sensitive)
            throws IOException {
        List<GrowthProfile.MemoryItem> items = store.profile().getMemories();
        for (int i = 0; i < items.size(); i++) {
            GrowthProfile.MemoryItem old = items.get(i);
            if (Objects.equals(old.id(), id)) {
                String nextContent = content != null ? normalizeContent(content) : old.content();
                String nextEvidence = evidence != null ? blankToNull(evidence) : old.evidence();
                rejectForbidden(nextContent);
                rejectForbidden(nextEvidence);
                GrowthProfile.MemoryItem duplicate = findDuplicate(nextContent, old);
                if (duplicate != null) {
                    throw new IllegalArgumentException("Duplicate memory content is already stored");
                }
                GrowthProfile.MemoryItem updated = new GrowthProfile.MemoryItem(
                        old.id(),
                        type != null ? normalizeType(type) : old.type(),
                        nextContent,
                        nextEvidence,
                        confidence != null ? clampConfidence(confidence) : old.confidence(),
                        status != null ? normalizeStatus(status) : old.status(),
                        sensitive != null ? sensitive : old.sensitive(),
                        old.approvalPolicy(),
                        old.source(),
                        old.sourceSessionId(),
                        old.sourceMessageIds(),
                        old.createdAt(),
                        Instant.now());
                List<GrowthProfile.MemoryItem> snapshot = new ArrayList<>(items);
                items.set(i, updated);
                saveOrRestore(items, snapshot);
                return updated;
            }
        }
        return null;
    }

    public synchronized boolean delete(String id) throws IOException {
        List<GrowthProfile.MemoryItem> items = store.profile().getMemories();
        List<GrowthProfile.MemoryItem> snapshot = new ArrayList<>(items);
        boolean removed = items.removeIf(m -> Objects.equals(m.id(), id));
        if (removed) saveOrRestore(items, snapshot);
        return removed;
    }

    private void saveOrRestore(List<GrowthProfile.MemoryItem> items, List<GrowthProfile.MemoryItem> snapshot)
            throws IOException {
        try {
            store.save();
        } catch (IOException | RuntimeException e) {
            items.clear();
            items.addAll(snapshot);
            throw e;
        }
    }

    private GrowthProfile.MemoryItem findDuplicate(String content) {
        return findDuplicate(content, null);
    }

    private GrowthProfile.MemoryItem findDuplicate(String content, GrowthProfile.MemoryItem excluded) {
        String normalized = fingerprint(content);
        return store.profile().getMemories().stream()
                .filter(m -> !"rejected".equals(m.status()))
                .filter(m -> !isSameMemory(m, excluded))
                .filter(m -> normalized.equals(fingerprint(m.content())))
                .findFirst()
                .orElse(null);
    }

    private static boolean isSameMemory(GrowthProfile.MemoryItem item, GrowthProfile.MemoryItem other) {
        if (other == null) return false;
        if (item.id() != null || other.id() != null) return Objects.equals(item.id(), other.id());
        return item == other;
    }

    private static void rejectForbidden(String content) {
        if (content == null) {
            return;
        }
        if (CREDENTIAL_LIKE_PATTERN.matcher(content).matches()) {
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
        String normalized = canonical(type);
        if (normalized.isEmpty()) return "note";
        return switch (normalized) {
            case "goal", "preference", "project", "pattern", "fact", "note" -> normalized;
            default -> "note";
        };
    }

    private static String normalizeStatus(String status) {
        String normalized = canonical(status);
        if (normalized.isEmpty()) return "active";
        return switch (normalized) {
            case "active", "pending", "disabled", "rejected" -> normalized;
            default -> "active";
        };
    }

    private static String normalizeSource(String source) {
        return source == null || source.isBlank() ? "ui_manual" : source.trim();
    }

    private static String normalizeApprovalPolicy(String approvalPolicy) {
        String normalized = canonical(approvalPolicy);
        return switch (normalized) {
            case "auto", "confirm" -> normalized;
            default -> "confirm";
        };
    }

    private static List<String> normalizeMessageIds(List<String> sourceMessageIds) {
        if (sourceMessageIds == null) {
            return List.of();
        }
        return sourceMessageIds.stream()
                .filter(Objects::nonNull)
                .filter(id -> !id.isBlank())
                .toList();
    }

    private static String canonical(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
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

    private static int compareUpdatedAtDescendingNullsLast(GrowthProfile.MemoryItem left,
                                                           GrowthProfile.MemoryItem right) {
        Instant leftUpdatedAt = left.updatedAt();
        Instant rightUpdatedAt = right.updatedAt();
        if (leftUpdatedAt == null && rightUpdatedAt == null) return 0;
        if (leftUpdatedAt == null) return 1;
        if (rightUpdatedAt == null) return -1;
        return rightUpdatedAt.compareTo(leftUpdatedAt);
    }
}
