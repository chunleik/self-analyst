package com.selfanalyst.desktop.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
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
        return "current=" + factsKey(current) + "|today=" + factsKey(today);
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
        List<String> apps = new ArrayList<>(facts.topApps() == null ? List.of() : facts.topApps());
        apps.sort(Comparator.naturalOrder());
        return String.join(",", apps)
                + ";a" + bucketMinutes(facts.activeTime())
                + ";k" + bucketMinutes(facts.afkTime())
                + ";s" + (facts.switchCount() / SWITCH_BUCKET);
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
