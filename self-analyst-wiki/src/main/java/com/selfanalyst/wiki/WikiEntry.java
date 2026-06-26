package com.selfanalyst.wiki;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record WikiEntry(
        String id,
        WikiLevel level,
        Instant periodStart,
        Instant periodEnd,
        String timezone,
        WikiStatus status,
        String summary,
        String primaryTask,
        List<TaskSegment> taskSegments,
        WikiMetrics metrics,
        List<String> sourceEntryIds,
        String model,
        String promptVersion,
        int retryCount,
        Instant nextRetryAt,
        String lastError,
        Instant createdAt,
        Instant updatedAt,
        Instant summarizedAt) {

    public record TaskSegment(
            String title,
            String summary,
            List<String> evidence,
            List<String> apps,
            String confidence) {
    }

    public record WikiMetrics(
            long activeSeconds,
            long afkSeconds,
            int switchCount,
            List<AppDuration> topApps,
            Map<String, Object> extra) {
    }

    public record AppDuration(String app, long seconds) implements Comparable<AppDuration> {
        @Override
        public int compareTo(AppDuration o) {
            return Long.compare(o.seconds, this.seconds);
        }
    }
}
