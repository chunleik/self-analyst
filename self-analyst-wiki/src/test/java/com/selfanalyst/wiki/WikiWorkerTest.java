package com.selfanalyst.wiki;

import com.selfanalyst.aw.store.Database;
import com.selfanalyst.aw.store.EventStore;
import com.selfanalyst.aw.store.PulseTimeConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class WikiWorkerTest {

    private WikiWorker worker;
    private WikiStore store;
    private Database awDatabase;

    @AfterEach
    void tearDown() throws Exception {
        if (worker != null) worker.shutdown();
        if (store != null) store.close();
        if (awDatabase != null) awDatabase.close();
    }

    @Test
    void runningWorkerEnqueuesRecentlyCompletedPeriodsWithoutBackfill(@TempDir Path dir) {
        store = new WikiStore(dir.resolve("llm-wiki.db"));
        awDatabase = new Database(dir.resolve("aw-data"));
        EventStore eventStore = new EventStore(awDatabase, PulseTimeConfig.DEFAULT);
        WikiFactBuilder factBuilder = new WikiFactBuilder(eventStore, 12_000);
        WikiSummarizer summarizer = new WikiSummarizer(prompt -> {
            throw new AssertionError("empty periods must be skipped without calling the LLM");
        });
        AtomicReference<Instant> now = new AtomicReference<>(
                Instant.parse("2026-08-20T02:30:00Z"));
        worker = new WikiWorker(store, factBuilder, summarizer,
                ZoneId.of("UTC"), Duration.ofSeconds(5), 3_600,
                false, null, now::get);

        worker.start();
        now.set(Instant.parse("2026-08-20T03:05:00Z"));
        worker.processOneRound();

        Instant expectedEnd = Instant.parse("2026-08-20T03:00:00Z");
        assertFalse(store.query(expectedEnd.minus(1, ChronoUnit.HOURS),
                        expectedEnd, WikiLevel.HOUR).isEmpty(),
                "a running worker must discover completed periods even when history backfill is off");
        assertFalse(store.query(null, null, null).stream()
                        .anyMatch(entry -> entry.periodEnd().isAfter(now.get())),
                "the injected current time must be the completion cutoff for every level");
    }

    @Test
    void delayedRoundCatchesUpEveryCompletedHourExactlyOnce(@TempDir Path dir) {
        store = new WikiStore(dir.resolve("llm-wiki.db"));
        awDatabase = new Database(dir.resolve("aw-data"));
        EventStore eventStore = new EventStore(awDatabase, PulseTimeConfig.DEFAULT);
        WikiFactBuilder factBuilder = new WikiFactBuilder(eventStore, 12_000);
        WikiSummarizer summarizer = new WikiSummarizer(prompt -> {
            throw new AssertionError("empty periods must be skipped without calling the LLM");
        });
        AtomicReference<Instant> now = new AtomicReference<>(
                Instant.parse("2026-08-20T00:30:00Z"));
        worker = new WikiWorker(store, factBuilder, summarizer,
                ZoneId.of("UTC"), Duration.ofSeconds(5), 3_600,
                false, null, now::get);

        worker.start();
        now.set(Instant.parse("2026-08-20T03:05:00Z"));
        worker.processOneRound();
        worker.processOneRound();

        assertEquals(4, store.query(
                Instant.parse("2026-08-19T23:00:00Z"),
                Instant.parse("2026-08-20T03:00:00Z"), WikiLevel.HOUR).size(),
                "a delayed round must catch up all closed hours without duplicating them");
    }

    @Test
    void parentWaitsUntilEveryExpectedChildPeriodExists(@TempDir Path dir) throws Exception {
        store = new WikiStore(dir.resolve("llm-wiki.db"));
        awDatabase = new Database(dir.resolve("aw-data"));
        EventStore eventStore = new EventStore(awDatabase, PulseTimeConfig.DEFAULT);
        worker = new WikiWorker(store, new WikiFactBuilder(eventStore, 12_000),
                new WikiSummarizer(prompt -> "{}"), ZoneId.of("UTC"),
                Duration.ofSeconds(5), 3_600, false, null);

        Instant weekStart = Instant.parse("2026-08-10T00:00:00Z");
        WikiEntry week = entry("week", WikiLevel.WEEK, weekStart,
                weekStart.plus(7, ChronoUnit.DAYS), WikiStatus.PENDING);
        store.upsert(entry("day-0", WikiLevel.DAY, weekStart,
                weekStart.plus(1, ChronoUnit.DAYS), WikiStatus.SUMMARIZED));

        Method canProcess = WikiWorker.class.getDeclaredMethod("canProcess", WikiEntry.class);
        canProcess.setAccessible(true);
        assertEquals(false, canProcess.invoke(worker, week),
                "a partial set of child summaries must not unlock a parent period");

        for (int day = 1; day < 7; day++) {
            Instant start = weekStart.plus(day, ChronoUnit.DAYS);
            store.upsert(entry("day-" + day, WikiLevel.DAY, start,
                    start.plus(1, ChronoUnit.DAYS), WikiStatus.SUMMARIZED));
        }
        assertEquals(true, canProcess.invoke(worker, week),
                "a parent may run after every expected child period is complete");
    }

    @Test
    void failedStartupBackfillIsRetriedFromTheOriginalCursor(@TempDir Path dir) {
        store = new FailFirstQueryWikiStore(dir.resolve("llm-wiki.db"));
        awDatabase = new Database(dir.resolve("aw-data"));
        EventStore eventStore = new EventStore(awDatabase, PulseTimeConfig.DEFAULT);
        AtomicReference<Instant> now = new AtomicReference<>(
                Instant.parse("2026-08-20T03:05:00Z"));
        worker = new WikiWorker(store, new WikiFactBuilder(eventStore, 12_000),
                new WikiSummarizer(prompt -> {
                    throw new AssertionError("empty periods must be skipped without calling the LLM");
                }), ZoneId.of("UTC"), Duration.ofSeconds(5), 3_600,
                true, null, now::get);

        worker.start();
        worker.processOneRound();

        Instant historicalHour = Instant.parse("2026-08-14T03:00:00Z");
        assertFalse(store.query(historicalHour, historicalHour.plus(1, ChronoUnit.HOURS),
                        WikiLevel.HOUR).isEmpty(),
                "a transient startup failure must not discard the seven-day backfill window");
    }

    @Test
    void parentSummaryUsesOnlyChildrenFromItsOwnTimezone(@TempDir Path dir) throws Exception {
        store = new WikiStore(dir.resolve("llm-wiki.db"));
        awDatabase = new Database(dir.resolve("aw-data"));
        EventStore eventStore = new EventStore(awDatabase, PulseTimeConfig.DEFAULT);
        AtomicReference<String> capturedPrompt = new AtomicReference<>();
        WikiSummarizer summarizer = new WikiSummarizer(prompt -> {
            capturedPrompt.set(prompt);
            return "{\"summary\":\"week\",\"primaryTask\":\"task\","
                    + "\"taskSegments\":[],\"metrics\":{}}";
        });
        worker = new WikiWorker(store, new WikiFactBuilder(eventStore, 12_000),
                summarizer, ZoneId.of("UTC"), Duration.ofSeconds(5), 3_600,
                false, null);

        Instant weekStart = Instant.parse("2026-08-10T00:00:00Z");
        WikiEntry week = entry("week", WikiLevel.WEEK, weekStart,
                weekStart.plus(7, ChronoUnit.DAYS), WikiStatus.PENDING);
        store.upsert(week);
        for (int day = 0; day < 7; day++) {
            Instant start = weekStart.plus(day, ChronoUnit.DAYS);
            store.upsert(summarizedEntry("utc-day-" + day, start,
                    start.plus(1, ChronoUnit.DAYS), "UTC", "UTC-DAY", 1));
        }
        store.upsert(summarizedEntry("foreign-day", weekStart,
                weekStart.plus(1, ChronoUnit.DAYS), "Asia/Shanghai", "WRONG-TZ", 999));

        Method processEntry = WikiWorker.class.getDeclaredMethod("processEntry", WikiEntry.class);
        processEntry.setAccessible(true);
        processEntry.invoke(worker, week);

        assertFalse(capturedPrompt.get().contains("WRONG-TZ"),
                "a parent prompt must not include overlapping children from another timezone");
        WikiEntry summarizedWeek = store.query(week.periodStart(), week.periodEnd(), WikiLevel.WEEK)
                .stream().filter(candidate -> candidate.id().equals(week.id())).findFirst().orElseThrow();
        assertEquals(7, summarizedWeek.metrics().activeSeconds(),
                "parent metrics must aggregate only the seven exact UTC children");
        assertEquals(summarizer.promptVersion(), summarizedWeek.promptVersion(),
                "persisted provenance must use the title-only summarizer prompt version");
    }

    @Test
    void incompleteFailedParentDoesNotBlockRetryableChild(@TempDir Path dir) {
        store = new WikiStore(dir.resolve("llm-wiki.db"));
        awDatabase = new Database(dir.resolve("aw-data"));
        EventStore eventStore = new EventStore(awDatabase, PulseTimeConfig.DEFAULT);
        AtomicReference<Instant> now = new AtomicReference<>(
                Instant.parse("2026-08-20T03:05:00Z"));
        worker = new WikiWorker(store, new WikiFactBuilder(eventStore, 12_000),
                new WikiSummarizer(prompt -> {
                    throw new AssertionError("empty child periods must be skipped without an LLM call");
                }), ZoneId.of("UTC"), Duration.ofSeconds(5), 3_600,
                false, null, now::get);

        store.upsert(entry("recent-hour", WikiLevel.HOUR,
                Instant.parse("2026-08-20T02:00:00Z"),
                Instant.parse("2026-08-20T03:00:00Z"), WikiStatus.SKIPPED));
        Instant weekStart = Instant.parse("2026-08-10T00:00:00Z");
        store.upsert(failedEntry("failed-week", WikiLevel.WEEK, weekStart,
                weekStart.plus(7, ChronoUnit.DAYS), 0));
        store.upsert(failedEntry("failed-day", WikiLevel.DAY, weekStart,
                weekStart.plus(1, ChronoUnit.DAYS), 1));
        for (int day = 1; day < 7; day++) {
            Instant start = weekStart.plus(day, ChronoUnit.DAYS);
            store.upsert(summarizedEntry("day-" + day, start,
                    start.plus(1, ChronoUnit.DAYS), "UTC", "day", 1));
        }

        worker.start();
        worker.processOneRound();

        WikiEntry child = store.query(weekStart, weekStart.plus(1, ChronoUnit.DAYS),
                        WikiLevel.DAY).stream()
                .filter(candidate -> candidate.id().equals("failed-day"))
                .findFirst().orElseThrow();
        assertEquals(WikiStatus.SKIPPED, child.status(),
                "a retryable child must run even when an incomplete failed parent sorts first");
    }

    private static WikiEntry entry(String id, WikiLevel level, Instant start,
                                   Instant end, WikiStatus status) {
        return new WikiEntry(id, level, start, end, "UTC", status,
                status == WikiStatus.SUMMARIZED ? "summary" : null,
                status == WikiStatus.SUMMARIZED ? "task" : null,
                List.of(), new WikiEntry.WikiMetrics(0, 0, 0, List.of(), Map.of()),
                List.of(), null, null, 0, null, null,
                Instant.parse("2026-08-20T00:00:00Z"),
                Instant.parse("2026-08-20T00:00:00Z"), null);
    }

    private static WikiEntry summarizedEntry(String id, Instant start, Instant end,
                                             String timezone, String summary, long activeSeconds) {
        return new WikiEntry(id, WikiLevel.DAY, start, end, timezone,
                WikiStatus.SUMMARIZED, summary, "task", List.of(),
                new WikiEntry.WikiMetrics(activeSeconds, 0, 0, List.of(), Map.of()),
                List.of(), "test", "wiki-v1", 0, null, null,
                Instant.parse("2026-08-20T00:00:00Z"),
                Instant.parse("2026-08-20T00:00:00Z"),
                Instant.parse("2026-08-20T00:00:00Z"));
    }

    private static WikiEntry failedEntry(String id, WikiLevel level, Instant start,
                                         Instant end, int retryCount) {
        return new WikiEntry(id, level, start, end, "UTC", WikiStatus.FAILED,
                null, null, List.of(),
                new WikiEntry.WikiMetrics(0, 0, 0, List.of(), Map.of()),
                List.of(), "test", "wiki-v1", retryCount, null, "failed",
                Instant.parse("2026-08-20T00:00:00Z"),
                Instant.parse("2026-08-20T00:00:00Z"), null);
    }

    private static final class FailFirstQueryWikiStore extends WikiStore {
        private boolean failNextQuery = true;

        private FailFirstQueryWikiStore(Path dbPath) {
            super(dbPath);
        }

        @Override
        public List<WikiEntry> query(Instant start, Instant end, WikiLevel level) {
            if (failNextQuery) {
                failNextQuery = false;
                throw new RuntimeException("simulated transient query failure");
            }
            return super.query(start, end, level);
        }
    }
}
