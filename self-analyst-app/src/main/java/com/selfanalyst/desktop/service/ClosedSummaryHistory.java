package com.selfanalyst.desktop.service;

import com.selfanalyst.wiki.WikiEntry;
import com.selfanalyst.wiki.WikiGenerationProgress;
import com.selfanalyst.wiki.WikiLevel;
import com.selfanalyst.wiki.WikiPeriod;
import com.selfanalyst.wiki.WikiPeriodFactory;
import com.selfanalyst.wiki.WikiStatus;
import com.selfanalyst.wiki.WikiStore;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Read-only, non-overlapping history for a closed day or half-day awaiting its final summary. */
final class ClosedSummaryHistory {
    private static final DateTimeFormatter LABEL = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private static final Set<String> PROGRESS_STATES = Set.of("queued", "quality", "period_budget", "global_budget",
            "configuration", "input", "transport", "timeout", "local_storage", "interrupted", "publish_failed");
    private final WikiStore store;
    private final ZoneId zone;
    private final Instant start;
    private final Instant end;
    private final Map<WikiLevel, List<WikiEntry>> entries = new EnumMap<>(WikiLevel.class);
    private final List<Map<String, Object>> summaries = new ArrayList<>();
    private final List<Map<String, Object>> dependencies = new ArrayList<>();

    private ClosedSummaryHistory(WikiStore store, SummaryWindowClassifier.Slot slot, ZoneId zone) {
        this.store = store;
        this.zone = zone;
        this.start = slot.start();
        this.end = slot.end();
    }

    static void attach(WikiStore store, SummaryWindowClassifier.Slot slot, ZoneId zone, Map<String, Object> result) {
        var history = new ClosedSummaryHistory(store, slot, zone);
        Map<String, Object> status = new LinkedHashMap<>();
        try {
            if (store == null) {
                status.put("state", "unavailable");
            } else {
                WikiPeriod period = new WikiPeriod(slot.wikiLevel(), slot.start(), slot.end(), zone.getId());
                WikiEntry root = history.exact(period);
                status.putAll(history.state(period, root));
                if (root == null || root.status() != WikiStatus.SKIPPED) {
                    history.children(period);
                    if (root != null && root.status() == WikiStatus.PENDING && !history.dependencies.isEmpty())
                        status.put("state", "waiting_dependencies");
                }
            }
        } catch (RuntimeException unavailable) {
            status.clear();
            status.put("state", "unavailable");
        }
        status.put("dependencies", List.copyOf(history.dependencies));
        if (status.containsKey("generationProgress")) result.put("generationProgress", status.get("generationProgress"));
        result.put("summaryStatus", status);
        result.put("partialSummaries", List.copyOf(history.summaries));
        result.put("incomplete", true);
        if (!history.summaries.isEmpty()) result.put("source", "wiki-partial");
    }

    private WikiEntry exact(WikiPeriod period) {
        return entries.computeIfAbsent(period.level(), level -> store.query(start, end, level)).stream()
                .filter(WikiEntry::currentStatistics)
                .filter(entry -> entry.level() == period.level() && zone.getId().equals(entry.timezone()))
                .filter(entry -> period.start().equals(entry.periodStart()) && period.end().equals(entry.periodEnd()))
                .findFirst().orElse(null);
    }

    /** Returns whether every immediate dependency is terminal, matching the worker's hierarchy. */
    private boolean children(WikiPeriod parent) {
        WikiLevel child = switch (parent.level()) {
            case DAY -> WikiLevel.HALF_DAY;
            case HALF_DAY -> WikiLevel.HOUR;
            default -> null;
        };
        if (child == null) return true;
        boolean complete = true;
        for (WikiPeriod period : WikiPeriodFactory.generate(parent.start(), parent.end(), child, zone)) {
            if (period.start().isBefore(parent.start()) || period.end().isAfter(parent.end())) continue;
            WikiEntry entry = exact(period);
            if (entry != null && entry.status() == WikiStatus.SUMMARIZED
                    && entry.summary() != null && !entry.summary().isBlank()) {
                Map<String, Object> summary = range(period);
                summary.put("summary", entry.summary());
                summary.put("headline", entry.primaryTask() == null ? "" : entry.primaryTask());
                summary.put("taskSegments", entry.taskSegments());
                summaries.add(summary);
            } else if (entry == null || entry.status() != WikiStatus.SKIPPED) {
                complete = false;
                boolean descendantsReady = children(period);
                // Report the actual blocking leaf; a failed parent also has its own actionable failure.
                if (descendantsReady || entry != null && entry.status() == WikiStatus.FAILED)
                    dependencies.add(state(period, entry));
            }
        }
        return complete;
    }

    private Map<String, Object> range(WikiPeriod period) {
        Map<String, Object> range = new LinkedHashMap<>();
        range.put("level", period.level().name());
        range.put("periodStart", period.start().toString());
        range.put("periodEnd", period.end().toString());
        range.put("periodLabel", LABEL.format(period.start().atZone(zone)) + " – " + LABEL.format(period.end().atZone(zone)));
        return range;
    }

    private Map<String, Object> state(WikiPeriod period, WikiEntry entry) {
        Map<String, Object> state = range(period);
        state.put("state", entry == null ? "missing" : entry.status().name().toLowerCase(Locale.ROOT));
        if (entry != null) {
            Map<String, Object> progress = safeProgress(entry);
            if (!progress.isEmpty()) state.put("generationProgress", progress);
        }
        return state;
    }

    private static Map<String, Object> safeProgress(WikiEntry entry) {
        Map<String, Object> progress = new LinkedHashMap<>();
        WikiGenerationProgress.from(entry).forEach((key, value) -> {
            if (key.equals("state") && value instanceof String text && PROGRESS_STATES.contains(text))
                progress.put(key, text);
            else if (key.equals("reason") && value instanceof String text && safeReason(text))
                progress.put(key, text);
            else if (!key.equals("state") && !key.equals("reason") && !key.equals("nextRetryAt")
                    && value instanceof Number number && Double.isFinite(number.doubleValue()) && number.doubleValue() >= 0)
                progress.put(key, value);
        });
        if (!progress.containsKey("reason") && safeReason(entry.lastError())) progress.put("reason", entry.lastError());
        if (entry.nextRetryAt() != null) progress.put("nextRetryAt", entry.nextRetryAt().toString());
        if (!progress.containsKey("state") && entry.lastError() != null
                && entry.lastError().startsWith("WIKI_EVIDENCE_")) progress.put("state", "quality");
        return progress;
    }

    private static boolean safeReason(String text) {
        return text != null && text.length() <= 120 && text.matches("WIKI_[A-Z0-9_]+(?::[A-Za-z.]+)?");
    }
}
