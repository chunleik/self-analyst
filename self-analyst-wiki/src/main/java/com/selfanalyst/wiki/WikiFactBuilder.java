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
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
    public static final String FACT_BUILDER_VERSION = "wiki-facts-evidence-v6";

    private final EventStore eventStore;
    private final String hostname;
    private final int maxContentChars;
    private final java.util.function.Supplier<Long> projectionLagSeconds;
    private final WikiPrivacyPolicy privacy;

    public WikiFactBuilder(EventStore eventStore, int maxContentChars) {
        this(eventStore, maxContentChars, () -> null);
    }

    public WikiFactBuilder(EventStore eventStore, int maxContentChars,
                           java.util.function.Supplier<Long> projectionLagSeconds) {
        this(eventStore, maxContentChars, projectionLagSeconds, WikiPrivacyPolicy.none());
    }

    public WikiFactBuilder(EventStore eventStore, int maxContentChars,
                           java.util.function.Supplier<Long> projectionLagSeconds,
                           WikiPrivacyPolicy privacy) {
        this.eventStore = eventStore;
        this.hostname = resolveHostname();
        this.maxContentChars = maxContentChars;
        this.projectionLagSeconds = projectionLagSeconds;
        this.privacy = privacy == null ? WikiPrivacyPolicy.none() : privacy;
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
        return buildFacts(period, maxContentChars);
    }

    /** Full metadata facts for bounded multi-stage synthesis; do not truncate before partitioning. */
    public WikiFacts buildCompleteFacts(WikiPeriod period) {
        return buildFacts(period, Integer.MAX_VALUE);
    }

    private WikiFacts buildFacts(WikiPeriod period, int titleBudget) {
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
        WikiTitleSampler.Selection sampled = WikiTitleSampler.sample(
                period, stats.activeEvents(), windowEvents, contentEvents, titleBudget, privacy);
        List<String> titleSamples = sampled.facts().stream().filter(f -> "window".equals(f.source()))
                .map(WikiTitleSampler.Fact::title).toList();
        List<String> contextTitleSamples = sampled.facts().stream().filter(f -> "content".equals(f.source()))
                .map(f -> "[" + f.app() + "] " + f.title()).toList();

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
                        "afkSecondsExact", stats.afkSeconds(), "appSecondsExact", stats.apps(),
                        "titleSampling", sampled.coverage()), sampled);
    }

    public WikiFacts buildFactsFromChildren(List<WikiEntry> childEntries, WikiPeriod period) {
        long activeSeconds = 0;
        long afkSeconds = 0;
        int switchCount = 0;
        Map<String, Double> appTotals = new LinkedHashMap<>();
        Map<String, Object> totals = new LinkedHashMap<>();
        List<String> summaries = new ArrayList<>();
        List<WikiTitleSampler.Fact> childFacts = new ArrayList<>();

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
            if (child.status() == WikiStatus.SUMMARIZED && !localEmpty(child)) addChildFacts(child, childFacts, summaries);
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
        totals.put("sourceEntryIds", childEntries.stream().map(WikiEntry::id).toList());
        activeSeconds = ((Number) totals.getOrDefault("activeSecondsExact", 0)).longValue();
        afkSeconds = ((Number) totals.getOrDefault("afkSecondsExact", 0)).longValue();
        return new WikiFacts(period, activeSeconds, afkSeconds, switchCount,
                topApps, List.of(), List.of(), summaries, FACT_BUILDER_VERSION,
                childEntries.stream().map(WikiEntry::projectorVersion)
                        .filter(Objects::nonNull).findFirst().orElse(null), coverage, totals,
                WikiTitleSampler.fromFacts(period, childFacts, Map.of("candidateFacts", childFacts.size())));
    }

    private static boolean localEmpty(WikiEntry child) {
        Object generation = child.metrics() == null || child.metrics().extra() == null
                ? null : child.metrics().extra().get("generation");
        return generation instanceof Map<?, ?> map && "local_empty".equals(map.get("mode"));
    }

    private static void addChildFacts(WikiEntry child, List<WikiTitleSampler.Fact> facts, List<String> summaries) {
        List<WikiEntry.TaskSegment> tasks = child.taskSegments();
        if (tasks.isEmpty()) {
            String app = child.metrics() != null && !child.metrics().topApps().isEmpty()
                    ? child.metrics().topApps().getFirst().app() : "Wiki";
            tasks = List.of(new WikiEntry.TaskSegment(
                    child.primaryTask() == null ? "历史活动" : child.primaryTask(),
                    child.summary() == null ? "" : child.summary(), List.of(), List.of(app), "low"));
        }
        int index = 0;
        for (WikiEntry.TaskSegment task : tasks) {
            List<String> refs = new ArrayList<>();
            List<String> apps = task.apps().isEmpty() ? List.of("Wiki") : task.apps();
            int appIndex = 0;
            for (String app : apps) {
                String id = "child:" + child.id() + ":" + index + ":" + appIndex++;
                refs.add(id);
                Double seconds = index == 0 && child.metrics() != null
                        ? (double) child.metrics().activeSeconds() : 0d;
                facts.add(new WikiTitleSampler.Fact(id, "wiki", app, task.title(), "inferred", seconds, 1,
                        List.of(new WikiTitleSampler.Interval(child.periodStart().toString(), child.periodEnd().toString(),
                                false, List.of(), 0)), 0));
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("entryId", child.id());
            item.put("start", child.periodStart().toString());
            item.put("end", child.periodEnd().toString());
            item.put("timezone", child.timezone());
            item.put("title", task.title());
            item.put("summary", task.summary());
            item.put("evidenceFactIds", refs);
            item.put("confidence", task.confidence());
            item.put("sourceCoverage", child.sourceCoverage());
            try { summaries.add(JSON.writeValueAsString(item)); }
            catch (com.fasterxml.jackson.core.JsonProcessingException error) {
                throw new IllegalStateException("WIKI_CHILD_FACT_SERIALIZATION");
            }
            index++;
        }
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
            Map<String, Object> statistics,
            WikiTitleSampler.Selection sampledTitles) {

        public WikiFacts withInput(WikiTitleSampler.Selection selection, List<String> children) {
            Map<String, Object> extra = new LinkedHashMap<>(statistics);
            extra.put("titleSampling", selection.coverage());
            return new WikiFacts(period, activeSeconds, afkSeconds, switchCount, topApps,
                    List.of(), List.of(), children, factBuilderVersion, projectorVersion,
                    sourceCoverage, extra, selection);
        }

        public WikiFacts(WikiPeriod period, long activeSeconds, long afkSeconds, int switchCount,
                         List<WikiEntry.AppDuration> topApps, List<String> titleSamples,
                         List<String> contextTitleSamples, List<String> childSummaries,
                         String factBuilderVersion, String projectorVersion,
                         Map<String, WikiEntry.SourceCoverage> sourceCoverage, Map<String, Object> statistics) {
            this(period, activeSeconds, afkSeconds, switchCount, topApps, titleSamples, contextTitleSamples,
                    childSummaries, factBuilderVersion, projectorVersion, sourceCoverage, statistics, null);
        }

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
