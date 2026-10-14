package com.selfanalyst.wiki;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class WikiRepairWorkerTest {
    @TempDir Path directory;
    private static final Instant DAY_START = Instant.parse("2026-10-10T04:00:00Z");
    private static final Instant NOON = DAY_START.plusSeconds(8 * 3600);
    private static final Instant DAY_END = DAY_START.plusSeconds(24 * 3600);
    private static final Instant LATER = Instant.parse("9999-12-31T00:00:00Z");

    private WikiEntry parent(String id, WikiLevel level, Instant start, Instant end, WikiStatus status) {
        return new WikiEntry(id, level, start, end, "UTC", status, null, null, List.of(),
                new WikiEntry.WikiMetrics(0, 0, 0, List.of(), Map.of()), List.of(), null, null, 0, null, null,
                DAY_START, DAY_START, null);
    }

    private void populate(WikiStore store, boolean anotherFailure) {
        for (int i = 0; i < 24; i++) {
            Instant start = DAY_START.plusSeconds(i * 3600L);
            if (i == 6) store.upsert(WikiRepairServiceTest.entry("target"));
            else {
                WikiEntry entry = parent("hour-" + i, WikiLevel.HOUR, start, start.plusSeconds(3600), WikiStatus.SKIPPED);
                store.upsert(entry);
                if (anotherFailure && i == 7) store.markFailed(entry.id(), "WIKI_EVIDENCE_UNSUPPORTED_CLAIM", LATER);
            }
        }
        store.upsert(parent("half", WikiLevel.HALF_DAY, DAY_START, NOON, WikiStatus.PENDING));
        store.upsert(parent("other-half", WikiLevel.HALF_DAY, NOON, DAY_END, WikiStatus.SKIPPED));
        store.upsert(parent("day", WikiLevel.DAY, DAY_START, DAY_END, WikiStatus.PENDING));
    }

    private void repair(WikiStore store) {
        var service = WikiRepairServiceTest.service(store); var view = WikiRepairServiceTest.ready(service);
        service.commit("session", view.draft().id(), view.draft().draftRevision(), "repair-key");
    }

    private WikiWorker worker(WikiStore store, WikiSummarizer summarizer) {
        return new WikiWorker(store, new WikiFactBuilder(null, 24000), summarizer, ZoneId.of("UTC"),
                Duration.ofSeconds(5), 3600, false, null, () -> DAY_END);
    }

    @Test void repairedLastHourRecoversMarkerAndContinuesHalfDayAndDayAfterRestart() {
        Path path = directory.resolve("wiki.db");
        try (WikiStore store = new WikiStore(path)) {
            populate(store, false); repair(store); assertEquals(1, store.pendingRepairFollowups(10).size());
        }
        AtomicInteger calls = new AtomicInteger();
        try (WikiStore store = new WikiStore(path)) {
            var worker = worker(store, new WikiSummarizer(prompt -> {
                calls.incrementAndGet(); return WikiSummaryPipelineTest.groundedResponse(prompt);
            }));
            try {
                worker.start(); worker.processOneRound();
                assertTrue(store.pendingRepairFollowups(10).isEmpty());
                assertEquals(WikiStatus.SUMMARIZED, store.versionedEntry("half").entry().status());
                worker.processOneRound();
                assertEquals(WikiStatus.SUMMARIZED, store.versionedEntry("day").entry().status());
                assertEquals(2, calls.get());
                assertEquals(100, store.versionedEntry("day").entry().metrics().activeSeconds());
            } finally { worker.shutdown(); }
        }
    }

    @Test void anotherFailedHourStillBlocksParentsAfterSuccessfulRepair() {
        try (WikiStore store = new WikiStore(directory.resolve("wiki.db"))) {
            populate(store, true); repair(store); AtomicInteger calls = new AtomicInteger();
            var worker = worker(store, new WikiSummarizer(prompt -> { calls.incrementAndGet(); return "{}"; }));
            try {
                worker.start(); worker.processOneRound(); worker.processOneRound();
                assertEquals(WikiStatus.PENDING, store.versionedEntry("half").entry().status());
                assertEquals(WikiStatus.PENDING, store.versionedEntry("day").entry().status()); assertEquals(0, calls.get());
                assertEquals(WikiStatus.SUMMARIZED, store.versionedEntry("target").entry().status());
            } finally { worker.shutdown(); }
        }
    }

    @Test void parentPeriodBudgetStillBlocksWithoutSpendingAfterRepair() {
        try (WikiStore store = new WikiStore(directory.resolve("wiki.db"));
             WikiGenerationStore ledger = new WikiGenerationStore(directory.resolve("generation.db"))) {
            populate(store, false); repair(store); AtomicInteger calls = new AtomicInteger();
            var period = new WikiPeriod(WikiLevel.HALF_DAY, DAY_START, NOON, "UTC");
            var limits = new WikiGenerationStore.BudgetLimits(1, 1_000_000);
            var reserved = ledger.reserve(WikiGenerationStore.periodKey(period), "a".repeat(64), "b".repeat(64), 1, limits);
            ledger.settle(reserved.callId(), 5L, 5L);
            var pipeline = new WikiSummaryPipeline(() -> new WikiSummaryPipeline.Session() {
                public String identity() { return "fake"; }
                public String complete(String prompt, Duration timeout) { calls.incrementAndGet(); return WikiSummaryPipelineTest.groundedResponse(prompt); }
            }, new WikiSummaryPipeline.Limits(24000, 32000, 6), ledger, limits);
            var worker = worker(store, pipeline);
            try {
                worker.start(); worker.processOneRound(); worker.processOneRound();
                assertEquals("period_budget", WikiGenerationProgress.state(store.versionedEntry("half").entry()));
                assertEquals(WikiStatus.PENDING, store.versionedEntry("day").entry().status());
                assertEquals(0, calls.get()); assertEquals(1, ledger.snapshot(WikiGenerationStore.periodKey(period)).calls());
                var builder = new WikiFactBuilder(null, 24000);
                var repairs = new WikiRepairService(store, entry -> WikiRepairService.buildRepairFacts(store, builder, entry),
                        WikiPrivacyPolicy::none, () -> "test");
                var opened = repairs.open("half-session", "half");
                assertEquals(WikiLevel.HALF_DAY, opened.target().level());
                var edited = repairs.edit("half-session", opened.draft().id(), 0,
                        WikiRepairServiceTest.candidate("查看数据库同步设计。", "inferred", List.of(opened.evidence().getFirst().id())));
                var valid = repairs.validate("half-session", edited.draft().id(), edited.draft().draftRevision());
                assertEquals("valid", valid.draft().state(), valid.draft().issues().toString());
                repairs.commit("half-session", valid.draft().id(), valid.draft().draftRevision(), "half-budget-repair");
                assertEquals(1, ledger.snapshot(WikiGenerationStore.periodKey(period)).calls());
                assertEquals(0, calls.get());
                worker.processOneRound();
                assertEquals(WikiStatus.SUMMARIZED, store.versionedEntry("day").entry().status());
                assertEquals(1, calls.get());
            } finally { worker.shutdown(); }
        }
    }

    @Test void inFlightOldWorkerCannotPublishSuccessOrFailureAfterRepair() throws Exception {
        for (boolean fail : List.of(false, true)) {
            try (WikiStore store = new WikiStore(directory.resolve("race-" + fail + ".db"))) {
                store.upsert(WikiRepairServiceTest.entry("target"));
                CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
                WikiFactBuilder builder = new WikiFactBuilder(null, 24000) {
                    @Override public WikiFacts buildFacts(WikiPeriod period) { return WikiRepairServiceTest.facts(WikiRepairServiceTest.entry("target")); }
                };
                WikiSummarizer summarizer = new WikiSummarizer(prompt -> "unused") {
                    @Override public SummaryResult summarize(WikiFactBuilder.WikiFacts facts, Duration timeout) {
                        started.countDown();
                        try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
                        catch (InterruptedException error) { throw new RuntimeException(error); }
                        if (fail) throw new IllegalArgumentException("WIKI_EVIDENCE_UNSUPPORTED_CLAIM:summary");
                        return new SummaryResult("old worker", "old worker", List.of(), WikiRepairServiceTest.entry("target").metrics());
                    }
                };
                WikiWorker worker = new WikiWorker(store, builder, summarizer, ZoneId.of("UTC"), Duration.ofSeconds(10), 3600,
                        false, null, () -> DAY_END);
                Method process = WikiWorker.class.getDeclaredMethod("processEntry", WikiEntry.class); process.setAccessible(true);
                try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
                    var background = executor.submit(() -> { try { process.invoke(worker, store.versionedEntry("target").entry()); }
                        catch (ReflectiveOperationException error) { throw new RuntimeException(error); } });
                    try {
                        assertTrue(started.await(10, TimeUnit.SECONDS)); repair(store); release.countDown(); background.get(10, TimeUnit.SECONDS);
                        WikiEntry saved = store.versionedEntry("target").entry();
                        assertEquals(WikiStatus.SUMMARIZED, saved.status()); assertEquals("user-repair", saved.model());
                        assertEquals(WikiRepairServiceTest.observed().summary(), saved.summary()); assertNull(saved.lastError());
                    } finally { release.countDown(); worker.shutdown(); }
                }
            }
        }
    }
}
