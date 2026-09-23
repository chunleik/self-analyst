package com.selfanalyst.desktop.service;

import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stable fingerprint for open-window local facts, plus a 15-minute freshness window.
 */
public final class SummaryFactFingerprint {

    public static final Duration FRESHNESS = Duration.ofMinutes(15);

    private static final int DURATION_BUCKET_MINUTES = 5;
    private static final int SWITCH_BUCKET = 10;
    private static final Pattern HOURS_MINUTES = Pattern.compile("(\\d+)小时(?:(\\d+)分钟)?");
    private static final Pattern MINUTES = Pattern.compile("(\\d+)分钟");
    private static final Pattern SECONDS = Pattern.compile("(\\d+)秒");

    private SummaryFactFingerprint() {
    }

    public static String of(SummaryService.LocalFacts current, SummaryService.LocalFacts today) {
        return com.selfanalyst.events.statistics.ActivityStatistics.VERSION + "|"
                + com.selfanalyst.events.statistics.ActivityCalendar.VERSION + "|"
                + com.selfanalyst.wiki.WikiFactBuilder.FACT_BUILDER_VERSION + "|"
                + SummaryPromptService.PROMPT_VERSION + "|current=" + factsKey(current) + "|today=" + factsKey(today);
    }

    public static boolean isFresh(String assembledAt, Instant now) {
        if (assembledAt == null || assembledAt.isBlank() || now == null) {
            return false;
        }
        try {
            Instant at = Instant.parse(assembledAt);
            return !at.plus(FRESHNESS).isBefore(now);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    public static boolean needsRefresh(String snapshotFingerprint, String assembledAt,
                                       String liveFingerprint, Instant now) {
        if (snapshotFingerprint == null || snapshotFingerprint.isBlank()) {
            return true;
        }
        if (!snapshotFingerprint.equals(liveFingerprint)) {
            return true;
        }
        return !isFresh(assembledAt, now);
    }

    private static String factsKey(SummaryService.LocalFacts facts) {
        if (facts == null) {
            return "empty";
        }
        return appsKey(facts)
                + ";a" + bucketMinutes(facts.activeTime())
                + ";k" + bucketMinutes(facts.afkTime())
                + ";s" + (facts.switchCount() / SWITCH_BUCKET)
                + ";q" + facts.coverage() + ";u" + (long) facts.unknownActivitySeconds()
                + ";titles=" + titleKey(facts);
    }

    private static String appsKey(SummaryService.LocalFacts facts) {
        if (facts.titleFacts().coverage().get("appSeconds") instanceof Map<?, ?> values) {
            List<Map.Entry<String, Double>> apps = new ArrayList<>();
            boolean valid = true;
            for (var entry : values.entrySet()) {
                if (!(entry.getKey() instanceof String app) || !(entry.getValue() instanceof Number seconds)
                        || !Double.isFinite(seconds.doubleValue()) || seconds.doubleValue() < 0) {
                    valid = false;
                    break;
                }
                apps.add(Map.entry(app, seconds.doubleValue()));
            }
            if (valid) {
                // Preserve changes in which app leads, but ignore formatting and duration drift
                // inside one bucket. Name breaks ties deterministically, independent of map order.
                apps.sort(Map.Entry.<String, Double>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()));
                return "apps-v2:" + apps.stream().limit(5)
                        .map(entry -> field(entry.getKey()) + (long) (entry.getValue() / (60 * DURATION_BUCKET_MINUTES)))
                        .collect(java.util.stream.Collectors.joining(","));
            }
        }
        // Compatibility facts may predate exact application durations.
        List<String> legacy = new ArrayList<>(facts.topApps() == null ? List.of() : facts.topApps());
        legacy.sort(Comparator.naturalOrder());
        return String.join(",", legacy);
    }

    private static String titleKey(SummaryService.LocalFacts facts) {
        // IDs and exact interval boundaries move as the current window advances. Topic identity
        // and evidence strength invalidate text; normal duration changes use the existing buckets.
        List<String> titles = facts.titleFacts().facts().stream().map(fact ->
                field(fact.source()) + field(fact.app()) + field(fact.title()) + field(fact.kind())
                        + (fact.activeSeconds() == null ? "observed" : "matched"))
                .sorted().toList();
        String canonical = String.join("\n", titles) + "|content="
                + facts.titleFacts().coverage().getOrDefault("contentStatus", "unknown");
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    private static String field(String value) {
        return value == null ? "-1:" : value.length() + ":" + value;
    }

    static int bucketMinutes(String formatted) {
        return parseMinutes(formatted) / DURATION_BUCKET_MINUTES;
    }

    static int parseMinutes(String formatted) {
        if (formatted == null || formatted.isBlank()) {
            return 0;
        }
        String text = formatted.trim().toLowerCase(Locale.ROOT);
        for (var language : com.selfanalyst.i18n.LanguageRegistry.bundled().supported()) {
            for (String unit : List.of("hoursMinutes", "hours", "minutes", "seconds")) {
                String template = com.selfanalyst.i18n.Messages.text(language, "duration." + unit).toLowerCase(Locale.ROOT);
                String regex = java.util.Arrays.stream(template.split("%d", -1))
                        .map(Pattern::quote).collect(java.util.stream.Collectors.joining("(\\d+)"));
                Matcher match = Pattern.compile(regex).matcher(text);
                if (!match.matches() || match.groupCount() == 0) continue;
                int value = Integer.parseInt(match.group(1));
                return switch (unit) {
                    case "hoursMinutes" -> value * 60 + Integer.parseInt(match.group(2));
                    case "hours" -> value * 60;
                    case "minutes" -> value;
                    default -> 0;
                };
            }
        }
        Matcher hours = HOURS_MINUTES.matcher(text);
        if (hours.find()) {
            int hour = Integer.parseInt(hours.group(1));
            int minute = hours.group(2) == null ? 0 : Integer.parseInt(hours.group(2));
            return hour * 60 + minute;
        }
        Matcher minutes = MINUTES.matcher(text);
        if (minutes.find()) {
            return Integer.parseInt(minutes.group(1));
        }
        Matcher seconds = SECONDS.matcher(text);
        if (seconds.find()) {
            return 0;
        }
        return 0;
    }
}
