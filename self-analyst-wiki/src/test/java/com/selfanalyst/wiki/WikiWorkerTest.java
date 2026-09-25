package com.selfanalyst.wiki;

import com.selfanalyst.events.store.Database;
import com.selfanalyst.events.store.EventStore;
import com.selfanalyst.events.store.PulseTimeConfig;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

class WikiWorkerTest {

    @Test
    void exhaustedPeriodDoesNotStarveAnotherPeriodAndRaisedLimitResumesWithoutReset(@TempDir Path dir) throws Exception {
        store = new WikiStore(dir.resolve("wiki.db"));
        awDatabase = new Database(dir.resolve("events"));
        var events = new EventStore(awDatabase, PulseTimeConfig.DEFAULT);
        Instant now = Instant.parse("2026-09-21T05:00:00Z");
        var exhausted = entry("exhausted", WikiLevel.HOUR, now.minusSeconds(3600), now, WikiStatus.PENDING);
        var ready = entry("ready", WikiLevel.HOUR, now.minusSeconds(7200), now.minusSeconds(3600), WikiStatus.PENDING);
        store.upsert(exhausted); store.upsert(ready);
        String bucket = "watcher-window_" + java.net.InetAddress.getLocalHost().getHostName();
        events.insertEvent(bucket, new com.selfanalyst.events.model.Event(now.minusSeconds(1800), 60, Map.of("app", "Editor", "title", "Order module")));
        events.insertEvent(bucket, new com.selfanalyst.events.model.Event(now.minusSeconds(5400), 60, Map.of("app", "Editor", "title", "Order docs")));
        var period = new WikiPeriod(WikiLevel.HOUR, exhausted.periodStart(), exhausted.periodEnd(), "UTC");
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        try (var ledger = new WikiGenerationStore(dir.resolve("generation.db"))) {
            var limits = new WikiGenerationStore.BudgetLimits(1, 1_000_000);
            var reservation = ledger.reserve(WikiGenerationStore.periodKey(period), "a".repeat(64), "b".repeat(64), 1, limits);
            ledger.settle(reservation.callId(), 1L, 1L);
            var pipeline = workerPipeline(ledger, limits, calls);
            worker = new WikiWorker(store, new WikiFactBuilder(events, 24000), pipeline, ZoneId.of("UTC"),
                    Duration.ofSeconds(5), 3600, false, null, () -> now);
            worker.start(); worker.processOneRound();
            var paused = store.query(exhausted.periodStart(), now, WikiLevel.HOUR).getFirst();
            assertEquals("period_budget", WikiGenerationProgress.state(paused));
            assertEquals(0, paused.retryCount()); assertEquals(0, calls.get());
            worker.processOneRound();
            assertEquals(WikiStatus.SUMMARIZED, store.query(ready.periodStart(), ready.periodEnd(), WikiLevel.HOUR).getFirst().status());
            assertEquals(1, calls.get());
            worker.shutdown();
            pipeline = workerPipeline(ledger, new WikiGenerationStore.BudgetLimits(2, 1_000_000), calls);
            worker = new WikiWorker(store, new WikiFactBuilder(events, 24000), pipeline, ZoneId.of("UTC"),
                    Duration.ofSeconds(5), 3600, false, null, () -> now);
            worker.start(); worker.processOneRound();
            assertEquals(WikiStatus.SUMMARIZED, store.query(exhausted.periodStart(), now, WikiLevel.HOUR).getFirst().status());
            assertEquals(2, ledger.snapshot(WikiGenerationStore.periodKey(period)).calls());
        }
    }

    @Test
    void configurationPauseWaitsForRevisionRatherThanRepeatedPaidCalls(@TempDir Path dir) throws Exception {
        store = new WikiStore(dir.resolve("wiki.db")); awDatabase = new Database(dir.resolve("events"));
        var events = new EventStore(awDatabase, PulseTimeConfig.DEFAULT);
        Instant now = Instant.parse("2026-09-21T05:00:00Z");
        String bucket = "watcher-window_" + java.net.InetAddress.getLocalHost().getHostName();
        events.insertEvent(bucket, new com.selfanalyst.events.model.Event(now.minusSeconds(1800), 60, Map.of("app", "Editor", "title", "Order module")));
        var revision = new java.util.concurrent.atomic.AtomicInteger();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        try (var ledger = new WikiGenerationStore(dir.resolve("generation.db"))) {
            var pipeline = new WikiSummaryPipeline(() -> new WikiSummaryPipeline.Session() {
                public String identity() { return "stable-model"; }
                public String configurationRevision() { return revision.toString(); }
                public void preflight() { if (revision.get() == 0) throw new WikiSummaryPipeline.CallFailure(
                        WikiSummaryPipeline.FailureKind.CONFIGURATION, "WIKI_MODEL_UNAVAILABLE", true); }
                public String complete(String prompt, Duration timeout) { calls.incrementAndGet(); return WikiSummaryPipelineTest.groundedResponse(prompt); }
            }, new WikiSummaryPipeline.Limits(24000, 32000, 6), ledger, WikiGenerationStore.BudgetLimits.defaults());
            worker = new WikiWorker(store, new WikiFactBuilder(events, 24000), pipeline, ZoneId.of("UTC"),
                    Duration.ofSeconds(5), 3600, false, null, () -> now);
            worker.start(); worker.processOneRound(); worker.processOneRound();
            assertEquals(0, calls.get());
            assertEquals("configuration", WikiGenerationProgress.state(store.query(now.minusSeconds(3600), now, WikiLevel.HOUR).getFirst()));
            revision.incrementAndGet(); worker.processOneRound();
            assertEquals(1, calls.get());
            assertEquals(WikiStatus.SUMMARIZED, store.query(now.minusSeconds(3600), now, WikiLevel.HOUR).getFirst().status());
        }
    }

    @Test
    void configurationChangedDuringFailingRequestStillWakesPause(@TempDir Path dir) throws Exception {
        store = new WikiStore(dir.resolve("wiki.db")); awDatabase = new Database(dir.resolve("events"));
        var events = new EventStore(awDatabase, PulseTimeConfig.DEFAULT);
        Instant now = Instant.parse("2026-09-21T05:00:00Z");
        events.insertEvent("watcher-window_" + java.net.InetAddress.getLocalHost().getHostName(),
                new com.selfanalyst.events.model.Event(now.minusSeconds(1800), 60, Map.of("app", "Editor", "title", "Order module")));
        var revision = new java.util.concurrent.atomic.AtomicInteger();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        try (var ledger = new WikiGenerationStore(dir.resolve("generation.db"))) {
            var pipeline = new WikiSummaryPipeline(() -> new WikiSummaryPipeline.Session() {
                final int leaseRevision = revision.get();
                public String identity() { return "stable-model"; }
                public String configurationRevision() { return Integer.toString(leaseRevision); }
                public String complete(String prompt, Duration timeout) {
                    calls.incrementAndGet();
                    if (leaseRevision == 0) {
                        revision.incrementAndGet(); // User fixes authentication before the old response arrives.
                        throw new WikiSummaryPipeline.CallFailure(WikiSummaryPipeline.FailureKind.CONFIGURATION,
                                "WIKI_AUTHENTICATION", false);
                    }
                    return WikiSummaryPipelineTest.groundedResponse(prompt);
                }
            }, new WikiSummaryPipeline.Limits(24000, 32000, 6), ledger, WikiGenerationStore.BudgetLimits.defaults());
            worker = new WikiWorker(store, new WikiFactBuilder(events, 24000), pipeline, ZoneId.of("UTC"),
                    Duration.ofSeconds(5), 3600, false, null, () -> now);
            worker.start(); worker.processOneRound();
            assertEquals("configuration", WikiGenerationProgress.state(store.query(now.minusSeconds(3600), now, WikiLevel.HOUR).getFirst()));
            worker.processOneRound();
            assertEquals(2, calls.get());
            assertEquals(WikiStatus.SUMMARIZED, store.query(now.minusSeconds(3600), now, WikiLevel.HOUR).getFirst().status());
        }
    }

    @Test
    void transientPauseScanFailureDoesNotConsumeStartupRecovery(@TempDir Path dir) throws Exception {
        store = new WikiStore(dir.resolve("wiki.db")) {
            boolean first = true;
            @Override public List<WikiEntry> findGenerationPaused() {
                if (first) { first = false; throw new IllegalStateException("transient pause scan"); }
                return super.findGenerationPaused();
            }
        };
        awDatabase = new Database(dir.resolve("events"));
        var events = new EventStore(awDatabase, PulseTimeConfig.DEFAULT);
        Instant now = Instant.parse("2026-09-21T05:00:00Z");
        var paused = entry("paused", WikiLevel.HOUR, now.minusSeconds(3600), now, WikiStatus.PENDING);
        store.upsert(paused);
        store.markGenerationFailure(paused, null, Map.of("state", "period_budget"),
                Instant.parse("9999-12-31T00:00:00Z"), false);
        events.insertEvent("watcher-window_" + java.net.InetAddress.getLocalHost().getHostName(),
                new com.selfanalyst.events.model.Event(now.minusSeconds(1800), 60, Map.of("app", "Editor", "title", "Order module")));
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        try (var ledger = new WikiGenerationStore(dir.resolve("generation.db"))) {
            worker = new WikiWorker(store, new WikiFactBuilder(events, 24000),
                    workerPipeline(ledger, WikiGenerationStore.BudgetLimits.defaults(), calls), ZoneId.of("UTC"),
                    Duration.ofSeconds(5), 3600, false, null, () -> now);
            worker.start(); worker.processOneRound();
            assertEquals(0, calls.get());
            worker.processOneRound();
            assertEquals(1, calls.get());
            assertEquals(WikiStatus.SUMMARIZED, store.query(now.minusSeconds(3600), now, WikiLevel.HOUR).getFirst().status());
        }
    }

    @Test
    void finalCheckpointRecoversARealPublicationFailureWithoutAnotherModelCall(@TempDir Path dir) throws Exception {
        store = new WikiStore(dir.resolve("wiki.db")) {
            boolean fail = true;
            @Override public void updateStatus(String id, WikiStatus status, String summary, String task,
                    List<WikiEntry.TaskSegment> segments, WikiEntry.WikiMetrics metrics, List<String> sources,
                    String model, String prompt, String factsVersion, String projector, Map<String, WikiEntry.SourceCoverage> coverage) {
                if (fail) { fail = false; throw new IllegalStateException("publication failure"); }
                super.updateStatus(id, status, summary, task, segments, metrics, sources, model, prompt, factsVersion, projector, coverage);
            }
        };
        awDatabase = new Database(dir.resolve("events"));
        var events = new EventStore(awDatabase, PulseTimeConfig.DEFAULT);
        Instant now = Instant.parse("2026-09-21T05:00:00Z");
        events.insertEvent("watcher-window_" + java.net.InetAddress.getLocalHost().getHostName(),
                new com.selfanalyst.events.model.Event(now.minusSeconds(1800), 60, Map.of("app", "Editor", "title", "Order module")));
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var clock = new AtomicReference<>(now);
        try (var ledger = new WikiGenerationStore(dir.resolve("generation.db"))) {
            worker = new WikiWorker(store, new WikiFactBuilder(events, 24000),
                    workerPipeline(ledger, new WikiGenerationStore.BudgetLimits(1, 1_000_000), calls), ZoneId.of("UTC"),
                    Duration.ofSeconds(5), 3600, false, null, clock::get);
            worker.start(); worker.processOneRound();
            assertEquals("publish_failed", WikiGenerationProgress.state(store.query(now.minusSeconds(3600), now, WikiLevel.HOUR).getFirst()));
            clock.set(now.plusSeconds(121)); worker.processOneRound();
            var recovered = store.query(now.minusSeconds(3600), now, WikiLevel.HOUR).getFirst();
            assertEquals(WikiStatus.SUMMARIZED, recovered.status()); assertEquals(1, calls.get());
            org.junit.jupiter.api.Assertions.assertNull(recovered.lastError());
        }
    }

    private static WikiSummaryPipeline workerPipeline(WikiGenerationStore ledger, WikiGenerationStore.BudgetLimits limits,
                                                     java.util.concurrent.atomic.AtomicInteger calls) {
        return new WikiSummaryPipeline(() -> new WikiSummaryPipeline.Session() {
            public String identity() { return "worker-test-model"; }
            public String complete(String prompt, Duration timeout) { calls.incrementAndGet(); return WikiSummaryPipelineTest.groundedResponse(prompt); }
        }, new WikiSummaryPipeline.Limits(24000, 32000, 6), ledger, limits);
    }

    private WikiWorker worker;
    private WikiStore store;
    private Database awDatabase;

    @Test
    void statisticsNarrativeFailsSafelyWithoutCommittingSummary(@TempDir Path dir) throws Exception {
        store = new WikiStore(dir.resolve("llm-wiki.db"));
        awDatabase = new Database(dir.resolve("events"));
        EventStore events = new EventStore(awDatabase, PulseTimeConfig.DEFAULT);
        Instant now = Instant.parse("2026-09-21T05:00:00Z");
        String host = java.net.InetAddress.getLocalHost().getHostName();
        events.insertEvent("watcher-window_" + host, new com.selfanalyst.events.model.Event(
                now.minusSeconds(1800), 60, Map.of("app", "Editor", "title", "Database module")));
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        worker = new WikiWorker(store, new WikiFactBuilder(events, 24000), new WikiSummarizer(prompt -> {
            calls.incrementAndGet();
            return "{\"summary\":\"AFK覆盖为partial PRIVATE_NARRATIVE\",\"primaryTask\":\"开发\"}";
        }), ZoneId.of("UTC"), Duration.ofSeconds(5), 3600, false, null, () -> now);
        worker.start();
        worker.processOneRound();
        assertEquals(1, calls.get());
        WikiEntry entry = store.query(now.minusSeconds(3600), now, WikiLevel.HOUR).getFirst();
        assertEquals(WikiStatus.FAILED, entry.status());
        assertEquals("WIKI_NARRATIVE_STATISTICS:summary", entry.lastError());
        org.junit.jupiter.api.Assertions.assertNull(entry.summary());
        org.junit.jupiter.api.Assertions.assertNotNull(entry.nextRetryAt());
    }

    @Test
    void afkOnlyAndNoiseOnlyHoursCompleteLocallyWithoutAModelCall(@TempDir Path dir) throws Exception {
        store = new WikiStore(dir.resolve("wiki.db"));
        awDatabase = new Database(dir.resolve("events"));
        var events = new EventStore(awDatabase, PulseTimeConfig.DEFAULT);
        Instant emptyStart = Instant.parse("2026-09-21T03:00:00Z");
        Instant afkStart = emptyStart.plusSeconds(3600);
        Instant noiseStart = afkStart.plusSeconds(3600);
        Instant now = noiseStart.plusSeconds(3600);
        String host = java.net.InetAddress.getLocalHost().getHostName();
        events.insertEvent("watcher-afk_" + host, new com.selfanalyst.events.model.Event(
                afkStart.plusSeconds(30), 3000, Map.of("status", "afk")));
        events.insertEvent("watcher-window_" + host, new com.selfanalyst.events.model.Event(
                noiseStart.plusSeconds(20), 180, Map.of("app", "LockApp.exe", "title", "Windows 默认锁屏界面")));
        store.upsert(entry("empty", WikiLevel.HOUR, emptyStart, afkStart, WikiStatus.PENDING));
        store.upsert(entry("afk", WikiLevel.HOUR, afkStart, noiseStart, WikiStatus.PENDING));
        store.upsert(entry("noise", WikiLevel.HOUR, noiseStart, now, WikiStatus.PENDING));
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        try (var ledger = new WikiGenerationStore(dir.resolve("generation.db"))) {
            var pipeline = workerPipeline(ledger, WikiGenerationStore.BudgetLimits.defaults(), calls);
            worker = new WikiWorker(store, new WikiFactBuilder(events, 24000), pipeline, ZoneId.of("UTC"),
                    Duration.ofSeconds(5), 3600, false, null, () -> now);
            worker.start();
            for (int round = 0; round < 6; round++) worker.processOneRound();
            WikiEntry skipped = store.query(emptyStart, afkStart, WikiLevel.HOUR).getFirst();
            WikiEntry afk = store.query(afkStart, noiseStart, WikiLevel.HOUR).getFirst();
            WikiEntry noise = store.query(noiseStart, now, WikiLevel.HOUR).getFirst();
            assertEquals(WikiStatus.SKIPPED, skipped.status());
            assertEquals("No events in period", skipped.lastError());
            assertEquals(WikiStatus.SUMMARIZED, afk.status());
            assertEquals("local", afk.model());
            assertEquals("local_empty", ((Map<?, ?>) afk.metrics().extra().get("generation")).get("mode"));
            assertEquals(0, ((Number) ((Map<?, ?>) afk.metrics().extra().get("generation")).get("calls")).intValue());
            assertTrue(afk.metrics().afkSeconds() > 0);
            assertEquals(0, afk.taskSegments().size());
            assertEquals(WikiStatus.SUMMARIZED, noise.status());
            assertEquals("local", noise.model());
            assertTrue(noise.metrics().activeSeconds() > 0);
            assertEquals(0, noise.taskSegments().size());
            assertEquals(0, calls.get());
            assertEquals(0, ledger.snapshot(WikiGenerationStore.periodKey(
                    new WikiPeriod(WikiLevel.HOUR, afkStart, noiseStart, "UTC"))).calls());
            assertEquals(0, ledger.snapshot(WikiGenerationStore.periodKey(
                    new WikiPeriod(WikiLevel.HOUR, noiseStart, now, "UTC"))).calls());
        }
    }

    @Test
    void sampledOutContextIsNotMisreportedAsNoEvents(@TempDir Path dir) throws Exception {
        store = new WikiStore(dir.resolve("llm-wiki.db"));
        awDatabase = new Database(dir.resolve("events"));
        EventStore events = new EventStore(awDatabase, PulseTimeConfig.DEFAULT);
        Instant now = Instant.parse("2026-09-21T05:00:00Z");
        String host = java.net.InetAddress.getLocalHost().getHostName();
        events.insertEvent("watcher-content_" + host, new com.selfanalyst.events.model.Event(
                now.minusSeconds(1800), 30, Map.of("schema_version", 2, "app", "Editor", "title", "Notes",
                "title_source", "window", "uia_chars", 0)));
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        worker = new WikiWorker(store, new WikiFactBuilder(events, 20), new WikiSummarizer(prompt -> {
            calls.incrementAndGet();
            org.junit.jupiter.api.Assertions.assertTrue(prompt.contains("selectedFacts=0"));
            return "{\"summary\":\"标题证据未纳入预算，无法判断任务\",\"primaryTask\":\"信息不足\",\"taskSegments\":[]}";
        }), ZoneId.of("UTC"), Duration.ofSeconds(5), 3600, false, null, () -> now);
        worker.start();
        worker.processOneRound();
        assertEquals(1, calls.get());
        assertEquals(WikiStatus.SUMMARIZED, store.query(now.minusSeconds(3600), now, WikiLevel.HOUR).getFirst().status());
    }

    @Test
    void historicalStatisticsDiscoveryIsBackgroundAndResumes(@TempDir Path dir) {
        Path wiki = dir.resolve("llm-wiki.db");
        store = new WikiStore(wiki);
        awDatabase = new Database(dir.resolve("events"));
        EventStore events = new EventStore(awDatabase, PulseTimeConfig.DEFAULT);
        Instant now = Instant.parse("2026-08-20T12:00:00Z");
        String host;
        try { host = java.net.InetAddress.getLocalHost().getHostName(); }
        catch (Exception error) { throw new IllegalStateException(error); }
        String bucket = "watcher-window_" + host;
        events.insertEvent(bucket, new com.selfanalyst.events.model.Event(now.minus(Duration.ofDays(20)), 60, Map.of("app", "editor")));
        WikiFactBuilder builder = new WikiFactBuilder(events, 12000);
        WikiSummarizer summarizer = new WikiSummarizer(prompt -> {
            throw new com.selfanalyst.wiki.usage.LlmUnavailableException();
        });
        worker = new WikiWorker(store, builder, summarizer, ZoneId.of("UTC"), Duration.ofSeconds(5), 3600, true, null, () -> now);
        worker.start();
        assertEquals(0, store.query(null, null, null).size(), "startup must not discover history synchronously");
        worker.processOneRound();
        Instant cursor = store.historyBefore();
        assertEquals(now.minus(Duration.ofDays(8)), cursor);
        worker.shutdown(); worker = null;
        store.close(); store = new WikiStore(wiki);
        assertEquals(cursor, store.historyBefore());
        worker = new WikiWorker(store, builder, summarizer, ZoneId.of("UTC"), Duration.ofSeconds(5), 3600, true, null, () -> now);
        worker.start(); worker.processOneRound();
        assertEquals(now.minus(Duration.ofDays(9)), store.historyBefore());
        assertEquals(1, events.countByBucket(bucket), "discovery never rewrites event facts");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (worker != null) worker.shutdown();
        if (store != null) store.close();
        if (awDatabase != null) awDatabase.close();
    }

    @Test
    void runningWorkerEnqueuesRecentlyCompletedPeriodsWithoutBackfill(@TempDir Path dir) {
        store = new WikiStore(dir.resolve("llm-wiki.db"));
        awDatabase = new Database(dir.resolve("events"));
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
        awDatabase = new Database(dir.resolve("events"));
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
    void halfDayWaitsForHoursAndDoesNotReadRawTitles(@TempDir Path dir) throws Exception {
        store = new WikiStore(dir.resolve("wiki.db"));
        awDatabase = new Database(dir.resolve("events"));
        var events = new EventStore(awDatabase, PulseTimeConfig.DEFAULT);
        Instant start = Instant.parse("2026-09-21T04:00:00Z");
        Instant noon = start.plusSeconds(8 * 3600);
        String host = java.net.InetAddress.getLocalHost().getHostName();
        events.insertEvent("watcher-window_" + host, new com.selfanalyst.events.model.Event(
                start.plusSeconds(60), 120, Map.of("app", "idea64.exe", "title", "订单模块")));
        store.upsert(entry("half", WikiLevel.HALF_DAY, start, noon, WikiStatus.PENDING));
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        worker = new WikiWorker(store, new WikiFactBuilder(events, 24000),
                new WikiSummarizer(prompt -> { calls.incrementAndGet(); return "{\"summary\":\"x\",\"primaryTask\":\"x\"}"; }),
                ZoneId.of("UTC"), Duration.ofSeconds(5), 3600, false, null, () -> noon);
        worker.start();
        worker.processOneRound();
        assertEquals(WikiStatus.PENDING, store.query(start, noon, WikiLevel.HALF_DAY).getFirst().status());
        assertEquals(0, calls.get());
    }

    @Test
    void parentWaitsUntilEveryExpectedChildPeriodExists(@TempDir Path dir) throws Exception {
        store = new WikiStore(dir.resolve("llm-wiki.db"));
        awDatabase = new Database(dir.resolve("events"));
        EventStore eventStore = new EventStore(awDatabase, PulseTimeConfig.DEFAULT);
        worker = new WikiWorker(store, new WikiFactBuilder(eventStore, 12_000),
                new WikiSummarizer(prompt -> "{}"), ZoneId.of("UTC"),
                Duration.ofSeconds(5), 3_600, false, null);

        Instant weekStart = Instant.parse("2026-08-10T04:00:00Z");
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
        awDatabase = new Database(dir.resolve("events"));
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

        worker.processOneRound(); // Failed background discovery retries on the following round.
        Instant historicalHour = Instant.parse("2026-08-14T03:00:00Z");
        assertFalse(store.query(historicalHour, historicalHour.plus(1, ChronoUnit.HOURS),
                        WikiLevel.HOUR).isEmpty(),
                "a transient startup failure must not discard the seven-day backfill window");
    }

    @Test
    void parentSummaryUsesOnlyChildrenFromItsOwnTimezone(@TempDir Path dir) throws Exception {
        store = new WikiStore(dir.resolve("llm-wiki.db"));
        awDatabase = new Database(dir.resolve("events"));
        EventStore eventStore = new EventStore(awDatabase, PulseTimeConfig.DEFAULT);
        AtomicReference<String> capturedPrompt = new AtomicReference<>();
        WikiSummarizer summarizer = new WikiSummarizer(prompt -> {
            capturedPrompt.set(prompt);
            return WikiSummaryPipelineTest.groundedResponse(prompt);
        });
        worker = new WikiWorker(store, new WikiFactBuilder(eventStore, 12_000),
                summarizer, ZoneId.of("UTC"), Duration.ofSeconds(5), 3_600,
                false, null);

        Instant weekStart = Instant.parse("2026-08-10T04:00:00Z");
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
        assertEquals(7, summarizedWeek.sourceEntryIds().size());
        org.junit.jupiter.api.Assertions.assertTrue(capturedPrompt.get().contains("2026-08-10T04:00:00Z"));
        org.junit.jupiter.api.Assertions.assertTrue(capturedPrompt.get().contains("child:utc-day-0:0:0"));
    }

    @Test
    void incompleteFailedParentDoesNotBlockRetryableChild(@TempDir Path dir) {
        store = new WikiStore(dir.resolve("llm-wiki.db"));
        awDatabase = new Database(dir.resolve("events"));
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
        Instant weekStart = Instant.parse("2026-08-10T04:00:00Z");
        store.upsert(failedEntry("failed-week", WikiLevel.WEEK, weekStart,
                weekStart.plus(7, ChronoUnit.DAYS), 0));
        store.upsert(failedEntry("failed-hour", WikiLevel.HOUR, weekStart,
                weekStart.plus(1, ChronoUnit.HOURS), 1));
        for (int day = 1; day < 7; day++) {
            Instant start = weekStart.plus(day, ChronoUnit.DAYS);
            store.upsert(summarizedEntry("day-" + day, start,
                    start.plus(1, ChronoUnit.DAYS), "UTC", "day", 1));
        }

        worker.start();
        worker.processOneRound();

        WikiEntry child = store.query(weekStart, weekStart.plus(1, ChronoUnit.HOURS),
                        WikiLevel.HOUR).stream()
                .filter(candidate -> candidate.id().equals("failed-hour"))
                .findFirst().orElseThrow();
        assertEquals(WikiStatus.SKIPPED, child.status(),
                "a retryable child must run even when an incomplete failed parent sorts first");
    }

    @Test
    void unconfiguredModelStaysPendingAndRecoversNextAttempt(@TempDir Path dir) throws Exception {
        store = new WikiStore(dir.resolve("llm-wiki.db"));
        awDatabase = new Database(dir.resolve("events"));
        var ready = new java.util.concurrent.atomic.AtomicBoolean();
        worker = new WikiWorker(store,
                new WikiFactBuilder(new EventStore(awDatabase, PulseTimeConfig.DEFAULT), 12_000),
                new WikiSummarizer(prompt -> {
                    if (!ready.get()) throw new com.selfanalyst.wiki.usage.LlmUnavailableException();
                    return WikiSummaryPipelineTest.groundedResponse(prompt);
                }), ZoneId.of("UTC"), Duration.ofSeconds(5), 3_600, false, null);
        Instant start = Instant.parse("2026-08-10T04:00:00Z");
        WikiEntry week = entry("waiting-week", WikiLevel.WEEK, start, start.plus(7, ChronoUnit.DAYS), WikiStatus.PENDING);
        store.upsert(week);
        for (int day = 0; day < 7; day++) {
            Instant dayStart = start.plus(day, ChronoUnit.DAYS);
            store.upsert(summarizedEntry("child-" + day, dayStart, dayStart.plus(1, ChronoUnit.DAYS), "UTC", "task", 1));
        }
        Method process = WikiWorker.class.getDeclaredMethod("processEntry", WikiEntry.class);
        process.setAccessible(true);
        process.invoke(worker, week);
        var waiting = store.query(start, week.periodEnd(), WikiLevel.WEEK).get(0);
        assertEquals(WikiStatus.PENDING, waiting.status());
        assertEquals(0, waiting.retryCount());
        ready.set(true);
        process.invoke(worker, waiting);
        assertEquals(WikiStatus.SUMMARIZED, store.query(start, week.periodEnd(), WikiLevel.WEEK).get(0).status());
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
