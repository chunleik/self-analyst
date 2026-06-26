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
    private static final int MAX_CONTENT_LEN = 800;
    private static final int MAX_WINDOW_SESSIONS = 20;

    private final EventStore eventStore;
    private final String hostname;
    private final int maxContentChars;

    public WikiFactBuilder(EventStore eventStore, int maxContentChars) {
        this.eventStore = eventStore;
        this.hostname = resolveHostname();
        this.maxContentChars = maxContentChars;
    }

    public WikiFacts buildFacts(WikiPeriod period) {
        String windowBucket = "aw-watcher-window_" + hostname;
        String afkBucket = "aw-watcher-afk_" + hostname;
        String contentBucket = "aw-watcher-content_" + hostname;

        List<Event> windowEvents = safeQuery(windowBucket, period.start(), period.end());
        List<Event> afkEvents = safeQuery(afkBucket, period.start(), period.end());
        List<Event> contentEvents = safeQuery(contentBucket, period.start(), period.end());

        long activeSeconds = computeActiveSeconds(windowEvents, period);
        long afkSeconds = computeAfkSeconds(afkEvents);
        int switchCount = windowEvents.size();
        List<WikiEntry.AppDuration> topApps = computeTopApps(windowEvents);
        List<String> titleSamples = sampleTitles(windowEvents);
        List<String> contentSamples = sampleContent(contentEvents);

        return new WikiFacts(period, activeSeconds, afkSeconds, switchCount,
                topApps, titleSamples, contentSamples);
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

        return new WikiFacts(period, activeSeconds, afkSeconds, switchCount,
                topApps, List.of(), List.of(), summaries);
    }

    private List<Event> safeQuery(String bucketId, Instant start, Instant end) {
        try {
            return eventStore.queryEvents(bucketId, 2000, start.toString(), end.toString());
        } catch (Exception e) {
            log.debug("Bucket {} query failed: {}", bucketId, e.getMessage());
            return List.of();
        }
    }

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

    /**
     * Group consecutive content events by (app, title), merge each session's
     * text into one representative snippet, then return at most MAX_WINDOW_SESSIONS entries.
     * This prevents the same window's 10 near-identical captures from each occupying
     * a separate sample slot.
     */
    private List<String> sampleContent(List<Event> contentEvents) {
        // 1. Build sessions: consecutive events sharing the same app+title
        record Session(String app, String title, List<String> texts) {}
        List<Session> sessions = new ArrayList<>();
        for (Event e : contentEvents) {
            String app  = (String) e.data().getOrDefault("app",  "");
            String title = (String) e.data().getOrDefault("title", "");
            String text  = (String) e.data().getOrDefault("text_content", "");
            if (text.isBlank()) continue;
            if (!sessions.isEmpty()) {
                Session last = sessions.get(sessions.size() - 1);
                if (last.app().equals(app) && last.title().equals(title)) {
                    last.texts().add(text);
                    continue;
                }
            }
            List<String> texts = new ArrayList<>();
            texts.add(text);
            sessions.add(new Session(app, title, texts));
        }

        // 2. For each session pick the longest sample (richest capture) and label it
        List<String> samples = new ArrayList<>();
        int totalChars = 0;
        for (Session s : sessions) {
            if (samples.size() >= MAX_WINDOW_SESSIONS) break;
            String best = s.texts().stream()
                    .max(Comparator.comparingInt(String::length))
                    .orElse("");
            if (best.isBlank()) continue;
            String snippet = best.length() > MAX_CONTENT_LEN
                    ? best.substring(0, MAX_CONTENT_LEN - 3) + "..." : best;
            String entry = "[" + s.app() + "] " + snippet;
            if (totalChars + entry.length() > maxContentChars) break;
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
            List<String> contentSamples,
            List<String> childSummaries) {

        public WikiFacts(WikiPeriod period, long activeSeconds, long afkSeconds, int switchCount,
                          List<WikiEntry.AppDuration> topApps, List<String> titleSamples,
                          List<String> contentSamples) {
            this(period, activeSeconds, afkSeconds, switchCount, topApps,
                    titleSamples, contentSamples, List.of());
        }
    }
}
