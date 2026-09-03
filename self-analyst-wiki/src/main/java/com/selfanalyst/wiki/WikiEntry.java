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
        Instant summarizedAt,
        String factBuilderVersion,
        String projectorVersion,
        Map<String, SourceCoverage> sourceCoverage) {

    public WikiEntry {
        taskSegments = taskSegments == null ? List.of() : List.copyOf(taskSegments);
        sourceEntryIds = sourceEntryIds == null ? List.of() : List.copyOf(sourceEntryIds);
        sourceCoverage = sourceCoverage == null ? Map.of() : Map.copyOf(sourceCoverage);
        if (sourceCoverage.size() > 32) throw new IllegalArgumentException("Wiki 来源覆盖最多 32 项");
        requireBounded(factBuilderVersion, 64, "factBuilderVersion");
        requireBounded(projectorVersion, 64, "projectorVersion");
        sourceCoverage.forEach((key, value) -> {
            requireBounded(key, 256, "sourceCoverage key");
            if (value == null) throw new IllegalArgumentException("sourceCoverage value 不能为空");
        });
    }

    public WikiEntry(String id, WikiLevel level, Instant periodStart, Instant periodEnd,
                     String timezone, WikiStatus status, String summary, String primaryTask,
                     List<TaskSegment> taskSegments, WikiMetrics metrics,
                     List<String> sourceEntryIds, String model, String promptVersion,
                     int retryCount, Instant nextRetryAt, String lastError,
                     Instant createdAt, Instant updatedAt, Instant summarizedAt) {
        this(id, level, periodStart, periodEnd, timezone, status, summary, primaryTask,
                taskSegments, metrics, sourceEntryIds, model, promptVersion, retryCount,
                nextRetryAt, lastError, createdAt, updatedAt, summarizedAt,
                null, null, Map.of());
    }

    private static void requireBounded(String value, int max, String field) {
        if (value != null && value.length() > max) {
            throw new IllegalArgumentException(field + " 长度不能超过 " + max);
        }
    }

    public record SourceCoverage(String status, Instant start, Instant end,
                                 Long projectionLagSeconds) {
        public SourceCoverage {
            requireBounded(status, 32, "sourceCoverage.status");
            if (projectionLagSeconds != null && projectionLagSeconds < 0) {
                throw new IllegalArgumentException("projectionLagSeconds 不能为负数");
            }
        }
    }

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
