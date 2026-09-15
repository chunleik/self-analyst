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
        Map<String, Object> behaviorAdvice,
        String statisticsVersion, String calendarVersion, String timezone) {
    public SummarySnapshot(String assembledAt, String currentWindowFingerprint,
                           Map<String, Object> current, List<Map<String, Object>> timeline,
                           Map<String, Object> behaviorAdvice) {
        this(assembledAt, currentWindowFingerprint, current, timeline, behaviorAdvice,
                com.selfanalyst.events.statistics.ActivityStatistics.VERSION,
                com.selfanalyst.events.statistics.ActivityCalendar.VERSION, java.time.ZoneId.systemDefault().getId());
    }

    public boolean compatible(java.time.ZoneId zone) {
        return com.selfanalyst.events.statistics.ActivityStatistics.VERSION.equals(statisticsVersion)
                && com.selfanalyst.events.statistics.ActivityCalendar.VERSION.equals(calendarVersion)
                && zone.getId().equals(timezone);
    }
}
