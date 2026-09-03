package com.selfanalyst.wiki;

import com.selfanalyst.aw.model.Event;
import com.selfanalyst.aw.store.EventStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.*;

public class WikiFactBuilder {

    private static final Logger log = LoggerFactory.getLogger(WikiFactBuilder.class);
    private static final int MAX_TITLE_LEN = 160;
    private static final int MAX_WINDOW_SESSIONS = 20;
    public static final String FACT_BUILDER_VERSION = "wiki-facts-v1";

    private final EventStore eventStore;
    private final String hostname;
    private final int maxContentChars;
    private final java.util.function.Supplier<Long> projectionLagSeconds;

    public WikiFactBuilder(EventStore eventStore, int maxContentChars) {
        this(eventStore, maxContentChars, () -> null);
    }

    public WikiFactBuilder(EventStore eventStore, int maxContentChars,
                           java.util.function.Supplier<Long> projectionLagSeconds) {
        this.eventStore = eventStore;
        this.hostname = resolveHostname();
        this.maxContentChars = maxContentChars;
        this.projectionLagSeconds = projectionLagSeconds;
    }

    public WikiFacts buildFacts(WikiPeriod period) {
        String windowBucket = "aw-watcher-window_" + hostname;
        String afkBucket = "aw-watcher-afk_" + hostname;
        String contentBucket = "aw-watcher-content_" + hostname;

        QueryResult window = safeQuery(windowBucket, period.start(), period.end());
        QueryResult afk = safeQuery(afkBucket, period.start(), period.end());
        QueryResult content = safeQuery(contentBucket, period.start(), period.end());
        List<Event> windowEvents = window.events();
        List<Event> afkEvents = afk.events();
        List<Event> contentEvents = content.events();

        long activeSeconds = computeActiveSeconds(windowEvents, period);
        long afkSeconds = computeAfkSeconds(afkEvents);
        int switchCount = windowEvents.size();
        List<WikiEntry.AppDuration> topApps = computeTopApps(windowEvents);
        List<String> titleSamples = sampleTitles(windowEvents);
        int titleChars = titleSamples.stream().mapToInt(String::length).sum();
        List<String> contextTitleSamples = sampleContextTitles(
                contentEvents, Math.max(0, maxContentChars - titleChars));

        Long lag = projectionLagSeconds.get();
        Map<String, WikiEntry.SourceCoverage> coverage = new LinkedHashMap<>();
        coverage.put("window", coverage(window.status(), period, lag));
        coverage.put("afk", coverage(afk.status(), period, lag));
        coverage.put("content", coverage(content.status(), period, lag));
        return new WikiFacts(period, activeSeconds, afkSeconds, switchCount,
                topApps, titleSamples, contextTitleSamples, List.of(),
                FACT_BUILDER_VERSION, eventStore.currentProjectorVersion(), coverage);
    }

    public WikiFacts buildFactsFromChildren(List<WikiEntry> childEntries, WikiPeriod period) {
        long activeSeconds = 0;
        long afkSeconds = 0;
        int switchCount = 0;
        Map<String, Long> appTotals = new LinkedHashMap<>();
        List<String> summaries = new ArrayList<>();

        for (WikiEntry child : childEntries) {
            if (child.metrics() != null) {
                activeSeconds += child.metrics().activeSeconds();
                afkSeconds += child.metrics().afkSeconds();
                switchCount += child.metrics().switchCount();
                for (WikiEntry.AppDuration ad : child.metrics().topApps()) {
                    appTotals.merge(ad.app(), ad.seconds(), Long::sum);
                }
            }
            if (child.summary() != null && !child.summary().isBlank()) {
                summaries.add(child.summary());
            }
            if (child.primaryTask() != null && !child.primaryTask().isBlank()) {
                summaries.add("主要任务: " + child.primaryTask());
            }
        }

        List<WikiEntry.AppDuration> topApps = appTotals.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(10)
                .map(e -> new WikiEntry.AppDuration(e.getKey(), e.getValue()))
                .toList();

        Map<String, WikiEntry.SourceCoverage> coverage = new LinkedHashMap<>();
        childEntries.forEach(child -> child.sourceCoverage().forEach(coverage::putIfAbsent));
        return new WikiFacts(period, activeSeconds, afkSeconds, switchCount,
                topApps, List.of(), List.of(), summaries, FACT_BUILDER_VERSION,
                childEntries.stream().map(WikiEntry::projectorVersion)
                        .filter(Objects::nonNull).findFirst().orElse(null), coverage);
    }

    private QueryResult safeQuery(String bucketId, Instant start, Instant end) {
        try {
            List<Event> events = eventStore.queryEvents(
                    bucketId, 2000, start.toString(), end.toString());
            String status = events.isEmpty() && !eventStore.bucketExists(bucketId)
                    ? "missing" : "complete";
            return new QueryResult(events, status);
        } catch (Exception e) {
            log.debug("Bucket {} query failed: {}", bucketId, e.getMessage());
            return new QueryResult(List.of(), "failed");
        }
    }

    private static WikiEntry.SourceCoverage coverage(
            String status, WikiPeriod period, Long lagSeconds) {
        String effective = lagSeconds != null && lagSeconds > 0 && "complete".equals(status)
                ? "lagging" : status;
        return new WikiEntry.SourceCoverage(effective, period.start(), period.end(), lagSeconds);
    }

    private record QueryResult(List<Event> events, String status) {}

    private long computeActiveSeconds(List<Event> windowEvents, WikiPeriod period) {
        if (windowEvents.isEmpty()) return 0;
        long total = (long) windowEvents.stream()
                .mapToDouble(Event::duration)
                .sum();
        long periodLen = period.end().getEpochSecond() - period.start().getEpochSecond();
        return Math.min(total, periodLen);
    }

    private long computeAfkSeconds(List<Event> afkEvents) {
        return (long) afkEvents.stream()
                .filter(e -> "afk".equals(e.data().get("status")))
                .mapToDouble(Event::duration)
                .sum();
    }

    private List<WikiEntry.AppDuration> computeTopApps(List<Event> windowEvents) {
        Map<String, Long> appTime = new LinkedHashMap<>();
        for (Event e : windowEvents) {
            String app = (String) e.data().getOrDefault("app", "unknown");
            appTime.merge(app, (long) e.duration(), Long::sum);
        }
        return appTime.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(10)
                .map(e -> new WikiEntry.AppDuration(e.getKey(), e.getValue()))
                .toList();
    }

    private List<String> sampleTitles(List<Event> windowEvents) {
        List<String> titles = new ArrayList<>();
        int totalChars = 0;
        for (Event e : windowEvents) {
            String title = (String) e.data().getOrDefault("title", "");
            if (title.isBlank()) continue;
            String trimmed = title.length() > MAX_TITLE_LEN
                    ? title.substring(0, MAX_TITLE_LEN - 3) + "..." : title;
            if (totalChars + trimmed.length() > maxContentChars) break;
            titles.add(trimmed);
            totalChars += trimmed.length();
        }
        return titles;
    }

    /** Sample unique application/window context titles without reading persisted body text. */
    private List<String> sampleContextTitles(List<Event> contentEvents, int charBudget) {
        if (charBudget <= 0) return List.of();
        Set<String> seen = new LinkedHashSet<>();
        List<String> samples = new ArrayList<>();
        int totalChars = 0;
        for (Event e : contentEvents) {
            String app  = (String) e.data().getOrDefault("app",  "");
            String title = (String) e.data().getOrDefault("title", "");
            String contextTitle = (String) e.data().getOrDefault("context_title", "");
            String effectiveTitle = !contextTitle.isBlank() ? contextTitle : title;
            if (effectiveTitle.isBlank()) continue;
            String kind = (String) e.data().getOrDefault("context_kind", "unknown");
            String key = app + "\u0000" + effectiveTitle + "\u0000" + kind;
            if (!seen.add(key)) continue;
            if (samples.size() >= MAX_WINDOW_SESSIONS) break;
            String normalized = effectiveTitle.length() > MAX_TITLE_LEN
                    ? effectiveTitle.substring(0, MAX_TITLE_LEN - 3) + "..."
                    : effectiveTitle;
            String entry = "[" + app + "] " + normalized;
            if (totalChars + entry.length() > charBudget) break;
            samples.add(entry);
            totalChars += entry.length();
        }
        return samples;
    }

    private static String resolveHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "localhost";
        }
    }

    public record WikiFacts(
            WikiPeriod period,
            long activeSeconds,
            long afkSeconds,
            int switchCount,
            List<WikiEntry.AppDuration> topApps,
            List<String> titleSamples,
            List<String> contextTitleSamples,
            List<String> childSummaries,
            String factBuilderVersion,
            String projectorVersion,
            Map<String, WikiEntry.SourceCoverage> sourceCoverage) {

        public WikiFacts(WikiPeriod period, long activeSeconds, long afkSeconds, int switchCount,
                          List<WikiEntry.AppDuration> topApps, List<String> titleSamples,
                          List<String> contextTitleSamples) {
            this(period, activeSeconds, afkSeconds, switchCount, topApps,
                    titleSamples, contextTitleSamples, List.of(),
                    FACT_BUILDER_VERSION, null, Map.of());
        }

        public WikiFacts(WikiPeriod period, long activeSeconds, long afkSeconds, int switchCount,
                         List<WikiEntry.AppDuration> topApps, List<String> titleSamples,
                         List<String> contextTitleSamples, List<String> childSummaries) {
            this(period, activeSeconds, afkSeconds, switchCount, topApps,
                    titleSamples, contextTitleSamples, childSummaries,
                    FACT_BUILDER_VERSION, null, Map.of());
        }
    }
}
