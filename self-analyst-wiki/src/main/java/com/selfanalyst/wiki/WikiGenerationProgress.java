package com.selfanalyst.wiki;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Safe public progress from stored generation metadata, independent of summary prose. */
public final class WikiGenerationProgress {
    private static final Set<String> PUBLIC_KEYS = Set.of("state", "reason", "calls", "tokens",
            "reservedTokens", "unsettledCalls", "estimatedCalls", "maxCalls", "maxTokens", "nextRetryAt");
    private WikiGenerationProgress() {}

    public static Map<String, Object> from(WikiEntry entry) {
        Map<String, Object> result = new LinkedHashMap<>();
        raw(entry).forEach((key, value) -> { if (PUBLIC_KEYS.contains(key)) result.put(key, value); });
        return result;
    }

    static Map<String, Object> raw(WikiEntry entry) {
        if (entry.metrics() == null || entry.metrics().extra() == null) return Map.of();
        Object value = entry.metrics().extra().get("generationProgress");
        if (!(value instanceof Map<?, ?> map)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> { if (key instanceof String text) result.put(text, item); });
        return result;
    }

    public static String state(WikiEntry entry) {
        Object value = raw(entry).get("state");
        return value instanceof String state ? state : "";
    }

    public static boolean paused(WikiEntry entry) {
        return Set.of("period_budget", "global_budget", "configuration", "input").contains(state(entry));
    }
}
