package com.selfanalyst.desktop.service;

import java.util.List;
import java.util.Map;

/**
 * Last successful desktop summary payload persisted for stale-while-revalidate.
 */
public record SummarySnapshot(
        String assembledAt,
        String currentWindowFingerprint,
        Map<String, Object> current,
        List<Map<String, Object>> timeline,
        Map<String, Object> behaviorAdvice) {
}
