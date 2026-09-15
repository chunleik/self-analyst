package com.selfanalyst.events.statistics;

import com.selfanalyst.events.model.Event;
import java.time.Instant;
import java.time.Duration;
import java.util.*;

/** 对同一投影快照做扫描线聚合，AFK、重复和冲突区间均只计一次。 */
public final class ActivityStatistics {
    public static final String VERSION = "activity-afk-v2";
    private ActivityStatistics() {}

    public record Result(Map<String, Double> apps, Map<String, Map<String, Double>> titles,
                         double activeSeconds, double afkSeconds, double unknownSeconds,
                         double uncoveredSeconds, double conflictSeconds, int switchCount,
                         int windowCount, List<Event> activeEvents) {
        public boolean estimated() { return uncoveredSeconds > 0 || conflictSeconds > 0; }
    }

    private record Window(int id, String app, String title) {}
    private record Change(Window window, int afk, int covered, boolean add) {}

    public static boolean unknown(String value) {
        return value == null || value.isBlank() || "unknown".equalsIgnoreCase(value.trim());
    }

    public static Instant end(Event event) {
        return event.timestamp().plusNanos(Math.round(event.duration() * 1_000_000_000.0));
    }

    public static Result compute(List<Event> windows, List<Event> afk, Instant start, Instant end) {
        if (!start.isBefore(end)) return empty();
        TreeMap<Instant, List<Change>> points = new TreeMap<>();
        int windowCount = 0;
        for (int i = 0; i < windows.size(); i++) {
            Event event = windows.get(i);
            if (!valid(event)) continue;
            Instant a = max(start, event.timestamp()), b = min(end, end(event));
            if (!a.isBefore(b)) continue;
            windowCount++;
            String app = Objects.toString(event.data().get("app"), "").trim();
            String title = Objects.toString(event.data().get("title"), "").trim();
            Window window = new Window(i, unknown(app) ? "" : app, unknown(title) ? "" : title);
            add(points, a, new Change(window, 0, 0, true));
            add(points, b, new Change(window, 0, 0, false));
        }
        for (Event event : afk) {
            if (!valid(event)) continue;
            Object status = event.data().get("status");
            if (!"afk".equals(status) && !"not-afk".equals(status)) continue;
            Instant a = max(start, event.timestamp()), b = min(end, end(event));
            if (!a.isBefore(b)) continue;
            int inactive = "afk".equals(status) ? 1 : 0;
            add(points, a, new Change(null, inactive, 1, true));
            add(points, b, new Change(null, inactive, 1, false));
        }
        Map<Integer, Window> current = new HashMap<>();
        Map<String, Long> apps = new TreeMap<>();
        Map<String, Map<String, Long>> titles = new TreeMap<>();
        List<Event> activeEvents = new ArrayList<>();
        Set<Integer> contributing = new HashSet<>();
        long active = 0, inactive = 0, unknown = 0, uncovered = 0, conflict = 0;
        int afkDepth = 0, coverage = 0;
        Instant previous = null;
        for (var point : points.entrySet()) {
            Instant at = point.getKey();
            if (previous != null) {
                long nanos = Duration.between(previous, at).toNanos();
                if (afkDepth > 0) inactive += nanos;
                else if (!current.isEmpty()) {
                    active += nanos;
                    if (coverage == 0) uncovered += nanos;
                    Set<String> names = new HashSet<>();
                    Set<String> labels = new HashSet<>();
                    for (Window w : current.values()) {
                        names.add(w.app());
                        if (!w.title().isEmpty()) labels.add(w.title());
                        contributing.add(w.id());
                    }
                    String app = names.size() == 1 ? names.iterator().next() : "";
                    String title = labels.size() == 1 ? labels.iterator().next() : "";
                    if (names.size() > 1) conflict += nanos;
                    if (app.isEmpty()) unknown += nanos;
                    else {
                        apps.merge(app, nanos, Long::sum);
                        if (!title.isEmpty()) titles.computeIfAbsent(app, k -> new TreeMap<>())
                                .merge(title, nanos, Long::sum);
                    }
                    activeEvents.add(new Event(previous, nanos / 1e9,
                            Map.of("app", app.isEmpty() ? "unknown" : app, "title", title)));
                }
            }
            for (Change change : point.getValue()) {
                int sign = change.add() ? 1 : -1;
                afkDepth += sign * change.afk();
                coverage += sign * change.covered();
                if (change.window() != null) {
                    if (change.add()) current.put(change.window().id(), change.window());
                    else current.remove(change.window().id());
                }
            }
            previous = at;
        }
        Map<String, Map<String, Double>> titleSeconds = new TreeMap<>();
        titles.forEach((app, values) -> titleSeconds.put(app, seconds(values)));
        return new Result(seconds(apps), titleSeconds, active / 1e9, inactive / 1e9,
                unknown / 1e9, uncovered / 1e9, conflict / 1e9, contributing.size(),
                windowCount, List.copyOf(activeEvents));
    }

    private static boolean valid(Event event) {
        return event != null && event.timestamp() != null && Double.isFinite(event.duration())
                && event.duration() > 0 && event.data() != null;
    }

    private static Result empty() {
        return new Result(Map.of(), Map.of(), 0, 0, 0, 0, 0, 0, 0, List.of());
    }
    private static Map<String, Double> seconds(Map<String, Long> values) {
        Map<String, Double> result = new TreeMap<>();
        values.forEach((key, value) -> result.put(key, value / 1e9));
        return Collections.unmodifiableMap(result);
    }
    private static void add(TreeMap<Instant, List<Change>> points, Instant at, Change change) {
        points.computeIfAbsent(at, k -> new ArrayList<>()).add(change);
    }
    private static Instant min(Instant a, Instant b) { return a.isBefore(b) ? a : b; }
    private static Instant max(Instant a, Instant b) { return a.isAfter(b) ? a : b; }
}
