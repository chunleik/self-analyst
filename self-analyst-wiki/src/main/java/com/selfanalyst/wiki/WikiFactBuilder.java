package com.selfanalyst.wiki;

import com.selfanalyst.events.model.Event;
import com.selfanalyst.events.statistics.ActivityStatistics;
import com.selfanalyst.events.store.EventStore;
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
    public static final String FACT_BUILDER_VERSION = "wiki-facts-afk-v2";

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

    public java.util.Optional<Instant> earliestEvent() {
        List<String> buckets = new ArrayList<>();
        for (String kind : List.of("window", "afk", "content")) {
            buckets.add("watcher-" + kind + "_" + hostname);
            buckets.add("aw-watcher-" + kind + "_" + hostname);
        }
        return eventStore.earliestEvent(buckets);
    }

    public WikiFacts buildFacts(WikiPeriod period) {
        List<String> buckets = new ArrayList<>();
        for (String kind : List.of("window", "afk", "content")) {
            buckets.add("watcher-" + kind + "_" + hostname);
            buckets.add("aw-watcher-" + kind + "_" + hostname);
        }
        var ranges = eventStore.queryIntersecting(buckets, period.start(), period.end());
        QueryResult window = select(ranges, "window");
        QueryResult afk = select(ranges, "afk");
        QueryResult content = select(ranges, "content");
        List<Event> windowEvents = window.events();
        List<Event> afkEvents = afk.events();
        List<Event> contentEvents = content.events();

        var stats = ActivityStatistics.compute(windowEvents, afkEvents, period.start(), period.end());
        long activeSeconds = (long) stats.activeSeconds();
        long afkSeconds = (long) stats.afkSeconds();
        int switchCount = stats.switchCount();
        List<WikiEntry.AppDuration> topApps = stats.apps().entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed()).limit(10)
                .map(e -> new WikiEntry.AppDuration(e.getKey(), e.getValue().longValue())).toList();
        List<String> titleSamples = sampleTitles(stats.activeEvents());
        int titleChars = titleSamples.stream().mapToInt(String::length).sum();
        List<String> contextTitleSamples = sampleContextTitles(
                contentEvents, Math.max(0, maxContentChars - titleChars));

        Long lag = projectionLagSeconds.get();
        Map<String, WikiEntry.SourceCoverage> coverage = new LinkedHashMap<>();
        coverage.put("window", coverage(window.status(), period, lag));
        coverage.put("afk", coverage(stats.estimated() && "complete".equals(afk.status()) ? "partial" : afk.status(), period, lag));
        coverage.put("content", coverage(content.status(), period, lag));
        return new WikiFacts(period, activeSeconds, afkSeconds, switchCount,
                topApps, titleSamples, contextTitleSamples, List.of(),
                FACT_BUILDER_VERSION, eventStore.currentProjectorVersion(), coverage,
                Map.of("unknownActivitySeconds", stats.unknownSeconds(), "uncoveredSeconds", stats.uncoveredSeconds(),
                        "conflictSeconds", stats.conflictSeconds(), "activeSecondsExact", stats.activeSeconds(),
                        "afkSecondsExact", stats.afkSeconds(), "appSecondsExact", stats.apps()));
    }

    public WikiFacts buildFactsFromChildren(List<WikiEntry> childEntries, WikiPeriod period) {
        long activeSeconds = 0;
        long afkSeconds = 0;
        int switchCount = 0;
        Map<String, Double> appTotals = new LinkedHashMap<>();
        Map<String, Object> totals = new LinkedHashMap<>();
        List<String> summaries = new ArrayList<>();

        for (WikiEntry child : childEntries) {
            if (child.metrics() != null) {
                activeSeconds += child.metrics().activeSeconds();
                afkSeconds += child.metrics().afkSeconds();
                switchCount += child.metrics().switchCount();
                Map<String, Object> extra = child.metrics().extra() == null ? Map.of() : child.metrics().extra();
                for (String key : List.of("unknownActivitySeconds", "uncoveredSeconds", "conflictSeconds",
                        "activeSecondsExact", "afkSecondsExact")) {
                    double value = extra.get(key) instanceof Number n ? n.doubleValue()
                            : key.equals("activeSecondsExact") ? child.metrics().activeSeconds()
                            : key.equals("afkSecondsExact") ? child.metrics().afkSeconds() : 0;
                    totals.merge(key, value, (a, b) -> ((Number) a).doubleValue() + ((Number) b).doubleValue());
                }
                if (extra.get("appSecondsExact") instanceof Map<?, ?> exact) {
                    exact.forEach((app, value) -> appTotals.merge(app.toString(), ((Number) value).doubleValue(), Double::sum));
                } else for (WikiEntry.AppDuration ad : child.metrics().topApps()) {
                    appTotals.merge(ad.app(), (double) ad.seconds(), Double::sum);
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
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(10)
                .map(e -> new WikiEntry.AppDuration(e.getKey(), e.getValue().longValue()))
                .toList();

        Map<String, WikiEntry.SourceCoverage> coverage = new LinkedHashMap<>();
        childEntries.forEach(child -> child.sourceCoverage().forEach((key, value) -> coverage.merge(key, value,
                (a, b) -> "complete".equals(a.status()) ? b : a)));
        totals.put("appSecondsExact", appTotals);
        activeSeconds = ((Number) totals.getOrDefault("activeSecondsExact", 0)).longValue();
        afkSeconds = ((Number) totals.getOrDefault("afkSecondsExact", 0)).longValue();
        return new WikiFacts(period, activeSeconds, afkSeconds, switchCount,
                topApps, List.of(), List.of(), summaries, FACT_BUILDER_VERSION,
                childEntries.stream().map(WikiEntry::projectorVersion)
                        .filter(Objects::nonNull).findFirst().orElse(null), coverage, totals);
    }

    private QueryResult select(Map<String, EventStore.EventRange> ranges, String kind) {
        var range = ranges.get("watcher-" + kind + "_" + hostname);
        if ("missing".equals(range.status())) range = ranges.get("aw-watcher-" + kind + "_" + hostname);
        return new QueryResult(range.events(), range.status());
    }

    private static WikiEntry.SourceCoverage coverage(
            String status, WikiPeriod period, Long lagSeconds) {
        String effective = lagSeconds != null && lagSeconds > 0 && "complete".equals(status)
                ? "lagging" : status;
        return new WikiEntry.SourceCoverage(effective, period.start(), period.end(), lagSeconds);
    }

    private record QueryResult(List<Event> events, String status) {}

    private List<String> sampleTitles(List<Event> windowEvents) {
        List<String> titles = new ArrayList<>();
        int totalChars = 0;
        for (Event e : windowEvents) {
            String title = (String) e.data().getOrDefault("title", "");
            if (ActivityStatistics.unknown(title)) continue;
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
            Map<String, WikiEntry.SourceCoverage> sourceCoverage,
            Map<String, Object> statistics) {

        public WikiFacts(WikiPeriod period, long activeSeconds, long afkSeconds, int switchCount,
                         List<WikiEntry.AppDuration> topApps, List<String> titleSamples,
                         List<String> contextTitleSamples, List<String> childSummaries,
                         String factBuilderVersion, String projectorVersion,
                         Map<String, WikiEntry.SourceCoverage> sourceCoverage) {
            this(period, activeSeconds, afkSeconds, switchCount, topApps, titleSamples, contextTitleSamples,
                    childSummaries, factBuilderVersion, projectorVersion, sourceCoverage, Map.of());
        }

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
