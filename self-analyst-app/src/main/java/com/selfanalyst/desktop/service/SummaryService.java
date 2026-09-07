package com.selfanalyst.desktop.service;

import com.selfanalyst.events.model.Event;
import com.selfanalyst.events.store.EventStore;
import com.selfanalyst.memory.GrowthProfile;
import com.selfanalyst.memory.MemoryStore;

import java.net.InetAddress;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.Comparator;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Produces local-only summary data from AW events without requiring an LLM.
 * <p>
 * Queries window and AFK buckets, computes per-period statistics, and formats
 * them as structured "local facts" suitable for direct display or later LLM enrichment.
 */
public class SummaryService implements SummaryFactSource {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;
    private static final ZoneId ZONE = ZoneId.systemDefault();

    private final EventStore eventStore;
    private final MemoryStore memoryStore;
    private final String windowBucket;
    private final String afkBucket;

    public SummaryService(EventStore eventStore, MemoryStore memoryStore) {
        this.eventStore = eventStore;
        this.memoryStore = memoryStore;
        String host = getHostname();
        this.windowBucket = "watcher-window_" + host;
        this.afkBucket = "watcher-afk_" + host;
    }

    String windowBucket() {
        return windowBucket;
    }

    String afkBucket() {
        return afkBucket;
    }

    // ── Public API ───────────────────────────────────────────────

    /** Current activity snapshot (last ~2 hours). */
    public LocalFacts getCurrentStatus() {
        return getCurrentStatus(Instant.now());
    }

    public LocalFacts getCurrentStatus(Instant now) {
        Instant twoHoursAgo = now.minus(Duration.ofHours(2));
        return computeFacts(twoHoursAgo, now, "现在");
    }

    @Override
    public LocalFacts currentStatus(Instant now) {
        return getCurrentStatus(now);
    }

    /** Local facts for an arbitrary period, without LLM enhancement. */
    public LocalFacts factsFor(Instant start, Instant end, String label) {
        return computeFacts(start, end, label);
    }

    /** Timeline entries for multiple periods. */
    public List<TimelineEntry> getTimeline() {
        Instant now = Instant.now();
        ZonedDateTime localNow = now.atZone(ZONE);
        LocalDate today = localNow.toLocalDate();

        List<TimelineEntry> entries = new ArrayList<>();

        // 当前: last ~2 hours
        Instant twoHoursAgo = now.minus(Duration.ofHours(2));
        entries.add(new TimelineEntry("current", "当前",
                computeFacts(twoHoursAgo, now, "当前")));

        // Today 00:00 → now
        Instant todayStart = today.atStartOfDay(ZONE).toInstant();
        entries.add(new TimelineEntry("today", "今天",
                computeFacts(todayStart, now, "今天")));

        // 上午: if current time >= 12:00, show 00:00 → 12:00
        if (localNow.getHour() >= 12) {
            Instant morningEnd = today.atTime(12, 0).atZone(ZONE).toInstant();
            entries.add(new TimelineEntry("morning", "上午",
                    computeFacts(todayStart, morningEnd, "上午")));
        }

        // Yesterday 00:00 → 23:59:59
        LocalDate yesterday = today.minusDays(1);
        Instant yDayStart = yesterday.atStartOfDay(ZONE).toInstant();
        Instant yDayEnd = yesterday.plusDays(1).atStartOfDay(ZONE).minusNanos(1).toInstant();
        entries.add(new TimelineEntry("yesterday", "昨天",
                computeFacts(yDayStart, yDayEnd, "昨天")));

        // Day before yesterday
        LocalDate dayBefore = today.minusDays(2);
        Instant dbStart = dayBefore.atStartOfDay(ZONE).toInstant();
        Instant dbEnd = dayBefore.plusDays(1).atStartOfDay(ZONE).minusNanos(1).toInstant();
        entries.add(new TimelineEntry("dayBefore", "前天",
                computeFacts(dbStart, dbEnd, "前天")));

        // This week (Monday → now)
        LocalDate thisMonday = today.with(DayOfWeek.MONDAY);
        entries.add(new TimelineEntry("thisWeek", "本周",
                computeFacts(thisMonday.atStartOfDay(ZONE).toInstant(), now, "本周")));

        // 最近两周: rolling 14-day window ending now
        Instant twoWeeksAgo = now.minus(14, java.time.temporal.ChronoUnit.DAYS);
        entries.add(new TimelineEntry("lastTwoWeeks", "最近两周",
                computeFacts(twoWeeksAgo, now, "最近两周")));

        // This month (1st → now)
        LocalDate firstOfMonth = today.withDayOfMonth(1);
        entries.add(new TimelineEntry("thisMonth", "本月",
                computeFacts(firstOfMonth.atStartOfDay(ZONE).toInstant(), now, "本月")));

        return entries;
    }

    /**
     * Computes multi-day behavior comparison data for advice generation.
     * Compares the most recent ~3 days against the preceding ~4 days.
     */
    @Override
    public BehaviorData behaviorData() {
        return getBehaviorData();
    }

    public BehaviorData getBehaviorData() {
        Instant now = Instant.now();
        Instant sevenDaysAgo = now.minus(7, ChronoUnit.DAYS);

        List<Event> windowEvents = safeQuery(windowBucket, sevenDaysAgo, now);

        if (windowEvents.isEmpty()) {
            return new BehaviorData(0, 0, 0, 0, 0, 0, 0, List.of());
        }

        // Group events by local date
        Map<LocalDate, List<Event>> byDay = new LinkedHashMap<>();
        for (Event e : windowEvents) {
            LocalDate day = e.timestamp().atZone(ZONE).toLocalDate();
            byDay.computeIfAbsent(day, k -> new ArrayList<>()).add(e);
        }

        List<LocalDate> sortedDays = new ArrayList<>(byDay.keySet());
        sortedDays.sort(Comparator.naturalOrder());

        int totalDays = sortedDays.size();

        // Split into recent (last half, min 1) and baseline (first half)
        int splitIdx = Math.max(1, totalDays / 2);
        List<LocalDate> recentDaysList = sortedDays.subList(Math.max(0, totalDays - splitIdx), totalDays);
        List<LocalDate> baselineDaysList = sortedDays.subList(0, Math.max(0, totalDays - splitIdx));

        // Compute per-period stats
        double recentEntertainment = 0;
        double recentEvening = 0;
        int recentSwitches = 0;
        for (LocalDate day : recentDaysList) {
            for (Event e : byDay.get(day)) {
                if (e.duration() <= 0) continue;
                String app = extractApp(e.data());
                if (isEntertainmentApp(app)) {
                    recentEntertainment += e.duration();
                    ZonedDateTime eventTime = e.timestamp().atZone(ZONE);
                    if (eventTime.getHour() >= 22) {
                        recentEvening += e.duration();
                    }
                }
                recentSwitches++;
            }
        }

        double baselineEntertainment = 0;
        double baselineEvening = 0;
        int baselineSwitches = 0;
        for (LocalDate day : baselineDaysList) {
            for (Event e : byDay.get(day)) {
                if (e.duration() <= 0) continue;
                String app = extractApp(e.data());
                if (isEntertainmentApp(app)) {
                    baselineEntertainment += e.duration();
                    ZonedDateTime eventTime = e.timestamp().atZone(ZONE);
                    if (eventTime.getHour() >= 22) {
                        baselineEvening += e.duration();
                    }
                }
                baselineSwitches++;
            }
        }

        int recentDayCount = Math.max(1, recentDaysList.size());
        int baselineDayCount = Math.max(1, baselineDaysList.size());

        // Top entertainment apps (across all days)
        Map<String, Double> entAppDurations = new LinkedHashMap<>();
        for (Event e : windowEvents) {
            if (e.duration() <= 0) continue;
            String app = extractApp(e.data());
            if (isEntertainmentApp(app)) {
                entAppDurations.merge(app, e.duration(), Double::sum);
            }
        }
        List<String> topEntApps = entAppDurations.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(5)
                .map(e -> e.getKey() + " " + formatDuration(e.getValue()))
                .toList();

        return new BehaviorData(
                totalDays,
                recentEntertainment / 60.0 / recentDayCount,
                baselineEntertainment / 60.0 / baselineDayCount,
                recentEvening / 60.0 / recentDayCount,
                baselineEvening / 60.0 / baselineDayCount,
                recentSwitches / recentDayCount,
                baselineSwitches / baselineDayCount,
                topEntApps
        );
    }

    // ── Compute helpers ──────────────────────────────────────────

    private LocalFacts computeFacts(Instant start, Instant end, String label) {
        List<Event> windowEvents = safeQuery(windowBucket, start, end);
        List<Event> afkEvents = safeQuery(afkBucket, start, end);

        // Aggregate window events by app name, and by (app -> title) for detail
        Map<String, Double> appDurations = new LinkedHashMap<>();
        Map<String, Map<String, Double>> appTitleDurations = new LinkedHashMap<>();
        double activeTime = 0.0;
        for (Event e : windowEvents) {
            if (e.duration() > 0) {
                String app = extractApp(e.data());
                String title = extractTitle(e.data());
                double dur = e.duration();
                appDurations.merge(app, dur, Double::sum);
                activeTime += dur;
                if (!title.isEmpty()) {
                    appTitleDurations.computeIfAbsent(app, k -> new LinkedHashMap<>())
                            .merge(title, dur, Double::sum);
                }
            }
        }

        // AFK time
        double afkTime = 0.0;
        for (Event e : afkEvents) {
            if (e.duration() > 0) {
                Object raw = e.data().get("status");
                if (raw != null && !"not-afk".equals(String.valueOf(raw))) {
                    afkTime += e.duration();
                }
            }
        }
        // Subtract AFK from active
        double effectiveActive = Math.max(0, activeTime - afkTime);

        // Top apps (sorted by duration desc, max 5)
        List<String> topApps = appDurations.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(5)
                .map(e -> e.getKey() + " " + formatDuration(e.getValue()))
                .toList();

        // Headline: show top app + its most-used window title
        String headline;
        if (topApps.isEmpty()) {
            headline = label + "暂无活动数据";
        } else {
            Map.Entry<String, Double> topAppEntry = appDurations.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .orElseThrow();
            String topAppName = topAppEntry.getKey();
            String topTitle = appTitleDurations.getOrDefault(topAppName, Map.of())
                    .entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(e -> abbreviate(e.getKey(), 40))
                    .orElse("");
            String titleSuffix = topTitle.isEmpty() ? "" : " (" + topTitle + ")";
            headline = "主要在" + topAppName + titleSuffix + ", " + formatDuration(topAppEntry.getValue());
        }

        // Evidence lines
        List<String> evidence = new ArrayList<>();
        if (!topApps.isEmpty()) {
            evidence.add("主要应用: " + String.join(", ", topApps));
        }
        // Top 3 window titles for the leading app
        String leadingApp = appDurations.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
        if (leadingApp != null) {
            List<String> topTitles = appTitleDurations.getOrDefault(leadingApp, Map.of())
                    .entrySet().stream()
                    .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                    .limit(3)
                    .map(e -> abbreviate(e.getKey(), 50) + " " + formatDuration(e.getValue()))
                    .toList();
            if (!topTitles.isEmpty()) {
                evidence.add("主要窗口: " + String.join("; ", topTitles));
            }
        }
        evidence.add("活跃时间: " + formatDuration(effectiveActive) + "，非活跃: " + formatDuration(afkTime));
        evidence.add("窗口切换次数: " + windowEvents.size());

        // Build goal context if memory is available
        String goalContext = "";
        if (memoryStore != null) {
            GrowthProfile profile = memoryStore.profile();
            List<GrowthProfile.Goal> activeGoals = profile.getGoals().stream()
                    .filter(GrowthProfile.Goal::active).toList();
            if (!activeGoals.isEmpty()) {
                goalContext = "活跃目标 " + activeGoals.size() + " 个: " +
                        activeGoals.stream().map(GrowthProfile.Goal::description)
                                .collect(Collectors.joining(", "));
            }
        }

        return new LocalFacts(headline, evidence, topApps,
                formatDuration(effectiveActive), formatDuration(afkTime),
                windowEvents.size(), goalContext);
    }

    private List<Event> safeQuery(String bucketId, Instant start, Instant end) {
        try {
            return eventStore.queryEvents(bucketId, 50_000, start.toString(), end.toString());
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String extractApp(Map<String, Object> data) {
        if (data == null) return "unknown";
        Object app = data.get("app");
        return app != null ? app.toString() : "unknown";
    }

    private static String extractTitle(Map<String, Object> data) {
        if (data == null) return "";
        Object title = data.get("title");
        return title != null ? title.toString().trim() : "";
    }

    private static String abbreviate(String s, int maxLen) {
        if (s == null || s.length() <= maxLen) return s;
        return s.substring(0, maxLen - 1) + "…";
    }

    private static final Set<String> ENTERTAINMENT_KEYWORDS = Set.of(
            "youtube", "bilibili", "douyin", "tiktok", "netflix", "iqiyi",
            "youku", "tencent video", "qq音乐", "网易云音乐", "spotify",
            "steam", "epic", "游戏", "video", "twitch", "斗鱼", "huya"
    );

    private static boolean isEntertainmentApp(String app) {
        if (app == null) return false;
        String lower = app.toLowerCase();
        for (String kw : ENTERTAINMENT_KEYWORDS) {
            if (lower.contains(kw)) return true;
        }
        return false;
    }

    static String formatDuration(double seconds) {
        if (seconds < 60) return (int) seconds + "秒";
        long mins = (long) (seconds / 60);
        if (mins < 60) return mins + "分钟";
        long hours = mins / 60;
        long remMins = mins % 60;
        return remMins > 0 ? hours + "小时" + remMins + "分钟" : hours + "小时";
    }

    private static String getHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }

    // ── Data classes ─────────────────────────────────────────────

    public record LocalFacts(
            String headline,
            List<String> evidence,
            List<String> topApps,
            String activeTime,
            String afkTime,
            int switchCount,
            String goalContext) {
    }

    public record TimelineEntry(
            String key,
            String label,
            LocalFacts facts) {
    }

    /**
     * Multi-day behavior comparison data for generating advice.
     * Compares recent period (last ~3 days) vs baseline (preceding ~4 days).
     */
    public record BehaviorData(
            int totalDays,
            double recentDailyEntertainmentMin,
            double baselineDailyEntertainmentMin,
            double recentDailyEveningMin,
            double baselineDailyEveningMin,
            int recentDailySwitches,
            int baselineDailySwitches,
            List<String> topEntertainmentApps) {

        public boolean hasEnoughData() {
            return totalDays >= 3;
        }
    }
}
