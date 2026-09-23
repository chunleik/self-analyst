package com.selfanalyst.wiki;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

import static com.selfanalyst.wiki.WikiSummaryPipelineTest.*;
import static org.junit.jupiter.api.Assertions.*;

class WikiSummaryPipelinePersistenceTest {
    @TempDir Path temp;
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Test void restartResumesMissingFinalAndAnotherRestartRestoresFinalWithoutModel() {
        Path path = temp.resolve("generation.db");
        AtomicInteger calls = new AtomicInteger();
        var input = facts(12);
        try (var store = new WikiGenerationStore(path)) {
            var first = pipeline(store, 1000, 12, "model", calls, prompt -> {
                if (prompt.startsWith("WIKI_STAGE=FINAL")) throw new IllegalStateException("private response");
                return groundedResponse(prompt);
            });
            var failure = assertThrows(WikiSummaryPipeline.CallFailure.class, () -> first.summarize(input, TIMEOUT));
            assertEquals(WikiSummaryPipeline.FailureKind.TRANSPORT, failure.kind());
            assertEquals("WIKI_MODEL_FAILURE", failure.code());
            assertTrue(calls.get() > 1);
        }
        int before = calls.get();
        WikiSummarizer.SummaryResult completed;
        try (var store = new WikiGenerationStore(path)) {
            completed = pipeline(store, 1000, 12, "model", calls, WikiSummaryPipelineTest::groundedResponse)
                    .summarize(input, TIMEOUT);
            assertEquals(1, calls.get() - before);
            assertTrue(((Number) generation(completed).get("cacheHits")).intValue() > 0);
        }
        before = calls.get();
        try (var store = new WikiGenerationStore(path)) {
            var resumed = pipeline(store, 1000, 12, "model", calls, prompt -> { throw new AssertionError("model called"); });
            var result = resumed.summarize(input, TIMEOUT);
            assertEquals(before, calls.get());
            assertEquals(0, generation(result).get("calls"));
            assertEquals(completed.taskSegments(), result.taskSegments());
            assertEquals(generation(completed).get("processedFacts"), generation(result).get("processedFacts"));
            assertEquals(generation(completed).get("omittedFacts"), generation(result).get("omittedFacts"));
            assertEquals(generation(completed).get("finalAvailableFacts"), generation(result).get("finalAvailableFacts"));
            resumed.markPublished(result);
        }
    }

    @Test void directCheckpointBypassesGatesAfterPublicationFailureAndCredentialRevisionChange() {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger revision = new AtomicInteger(1);
        AtomicBoolean blocked = new AtomicBoolean();
        Supplier<WikiSummaryPipeline.Session> sessions = () -> new WikiSummaryPipeline.Session() {
            public String identity() { return "stable-model-settings"; }
            public String configurationRevision() { return revision.toString(); }
            public void preflight() {
                if (blocked.get()) throw new WikiSummaryPipeline.CallFailure(
                        WikiSummaryPipeline.FailureKind.CONFIGURATION, "WIKI_MODEL_UNAVAILABLE", true);
            }
            public String complete(String prompt, Duration timeout) {
                calls.incrementAndGet(); return groundedResponse(prompt);
            }
        };
        try (var store = new WikiGenerationStore(temp.resolve("direct.db"))) {
            var pipeline = new WikiSummaryPipeline(sessions, new WikiSummaryPipeline.Limits(24000, 32000, 6),
                    store, WikiGenerationStore.BudgetLimits.defaults());
            var first = pipeline.summarize(facts(1), TIMEOUT);
            String stamp = pipeline.configurationStamp();
            blocked.set(true);
            revision.incrementAndGet();
            assertNotEquals(stamp, pipeline.configurationStamp());
            var resumed = pipeline.summarize(facts(1), TIMEOUT);
            assertEquals(1, calls.get());
            assertEquals(first.taskSegments(), resumed.taskSegments());
            assertEquals(1, generation(resumed).get("cacheHits"));
            assertEquals(0, generation(resumed).get("calls"));
            pipeline.markPublished(resumed);
            assertEquals(1, pipeline.progress(facts(1).period()).calls());
        }
    }

    @Test void modelInputAndPlanChangesIsolateCheckpointsWithoutResettingPeriodBudget() {
        AtomicInteger calls = new AtomicInteger();
        Path path = temp.resolve("budget.db");
        try (var store = new WikiGenerationStore(path)) {
            pipeline(store, 24000, 2, "first-model", calls, WikiSummaryPipelineTest::groundedResponse)
                    .summarize(facts(1), TIMEOUT);
        }
        try (var store = new WikiGenerationStore(path)) {
            pipeline(store, 20000, 2, "second-model", calls, WikiSummaryPipelineTest::groundedResponse)
                    .summarize(facts(2), TIMEOUT);
            var changed = pipeline(store, 18000, 2, "third-model", calls, WikiSummaryPipelineTest::groundedResponse);
            var failure = assertThrows(WikiPeriodBudgetException.class, () -> changed.summarize(facts(3), TIMEOUT));
            assertEquals(2, failure.snapshot().calls());
            assertEquals(2, calls.get());
            assertFalse(changed.canResume(facts(1).period()));
            var raised = pipeline(store, 18000, 3, "third-model", calls, WikiSummaryPipelineTest::groundedResponse);
            assertTrue(raised.canResume(facts(1).period()));
            raised.summarize(facts(3), TIMEOUT);
            assertEquals(3, calls.get());
            assertEquals(3, raised.progress(facts(1).period()).calls());
        }
    }

    @Test void qualityFailureChargesActualUsageAndPreRequestGateDoesNotCharge() {
        try (var store = WikiGenerationStore.inMemory()) {
            var pipeline = new WikiSummaryPipeline(() -> new WikiSummaryPipeline.Session() {
                public String identity() { return "quality"; }
                public String complete(String prompt, Duration timeout) { throw new AssertionError(); }
                public WikiSummaryPipeline.Completion completeDetailed(String prompt, Duration timeout) {
                    return new WikiSummaryPipeline.Completion("invalid private response", 20L, 30L);
                }
            }, new WikiSummaryPipeline.Limits(24000, 32000, 6), store, WikiGenerationStore.BudgetLimits.defaults());
            var failure = assertThrows(WikiSummaryPipeline.CallFailure.class, () -> pipeline.summarize(facts(1), TIMEOUT));
            assertEquals(WikiSummaryPipeline.FailureKind.QUALITY, failure.kind());
            assertFalse(failure.code().contains("private"));
            assertEquals(1, pipeline.progress(facts(1).period()).calls());
            assertEquals(50, pipeline.progress(facts(1).period()).tokens());

            var gated = new WikiSummaryPipeline(() -> new WikiSummaryPipeline.Session() {
                public String identity() { return "global-gate"; }
                public String complete(String prompt, Duration timeout) {
                    throw new WikiSummaryPipeline.CallFailure(WikiSummaryPipeline.FailureKind.GLOBAL_BUDGET,
                            "WIKI_GLOBAL_BUDGET", true);
                }
            }, new WikiSummaryPipeline.Limits(24000, 32000, 6), store, WikiGenerationStore.BudgetLimits.defaults());
            var budget = assertThrows(WikiSummaryPipeline.CallFailure.class, () -> gated.summarize(facts(1), TIMEOUT));
            assertEquals(WikiSummaryPipeline.FailureKind.GLOBAL_BUDGET, budget.kind());
            assertTrue(budget.beforeSend());
            assertEquals(1, pipeline.progress(facts(1).period()).calls());
            assertEquals(50, pipeline.progress(facts(1).period()).tokens());
        }
    }

    @Test void unknownFailureKeepsFullReservationAndTokenPauseRequiresSufficientIncrease() {
        AtomicInteger calls = new AtomicInteger();
        try (var store = WikiGenerationStore.inMemory()) {
            Supplier<WikiSummaryPipeline.Session> sessions = () -> new WikiSummaryPipeline.Session() {
                public String identity() { return "token-test"; }
                public long estimateInputTokens(String prompt) { return 100; }
                public String complete(String prompt, Duration timeout) {
                    calls.incrementAndGet(); throw new IllegalStateException("secret response");
                }
            };
            var pipeline = new WikiSummaryPipeline(sessions, new WikiSummaryPipeline.Limits(24000, 32000, 6),
                    store, new WikiGenerationStore.BudgetLimits(12, 5000));
            assertThrows(WikiSummaryPipeline.CallFailure.class, () -> pipeline.summarize(facts(1), TIMEOUT));
            assertEquals(4196, pipeline.progress(facts(1).period()).tokens());
            assertThrows(WikiPeriodBudgetException.class, () -> pipeline.summarize(facts(1), TIMEOUT));
            assertEquals(1, calls.get());
            assertFalse(pipeline.canResume(facts(1).period()));
            var insufficient = new WikiSummaryPipeline(sessions, new WikiSummaryPipeline.Limits(24000, 32000, 6),
                    store, new WikiGenerationStore.BudgetLimits(12, 8000));
            assertFalse(insufficient.canResume(facts(1).period()));
            var raised = new WikiSummaryPipeline(sessions, new WikiSummaryPipeline.Limits(24000, 32000, 6),
                    store, new WikiGenerationStore.BudgetLimits(12, 9000));
            assertTrue(raised.canResume(facts(1).period()));
        }
    }

    @Test void actualUsageAboveReservationIsNotTruncatedAndBlocksSubsequentInput() {
        try (var store = WikiGenerationStore.inMemory()) {
            AtomicInteger calls = new AtomicInteger();
            var pipeline = new WikiSummaryPipeline(() -> new WikiSummaryPipeline.Session() {
                public String identity() { return "large-output"; }
                public long estimateInputTokens(String prompt) { return 10; }
                public String complete(String prompt, Duration timeout) { throw new AssertionError(); }
                public WikiSummaryPipeline.Completion completeDetailed(String prompt, Duration timeout) {
                    calls.incrementAndGet();
                    return new WikiSummaryPipeline.Completion(groundedResponse(prompt), 20L, 10000L);
                }
            }, new WikiSummaryPipeline.Limits(24000, 32000, 6), store, new WikiGenerationStore.BudgetLimits(12, 5000));
            pipeline.summarize(facts(1), TIMEOUT);
            assertEquals(10020, pipeline.progress(facts(1).period()).tokens());
            assertThrows(WikiPeriodBudgetException.class, () -> pipeline.summarize(facts(2), TIMEOUT));
            assertEquals(1, calls.get());
            assertFalse(pipeline.canResume(facts(1).period()));
        }
    }

    @Test void timeoutConservativelySettlesThenAcceptsLateKnownUsageOnce() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean sessionClosed = new AtomicBoolean();
        try (var store = WikiGenerationStore.inMemory()) {
            var pipeline = new WikiSummaryPipeline(() -> new WikiSummaryPipeline.Session() {
                public String identity() { return "late-result"; }
                public long estimateInputTokens(String prompt) { return 10; }
                public void close() { sessionClosed.set(true); }
                public String complete(String prompt, Duration timeout) { throw new AssertionError(); }
                public WikiSummaryPipeline.Completion completeDetailed(String prompt, Duration timeout) {
                    entered.countDown();
                    boolean released = false;
                    while (!released) {
                        try { released = release.await(5, TimeUnit.SECONDS); }
                        catch (InterruptedException ignored) { /* Simulate a provider that cannot cancel. */ }
                    }
                    return new WikiSummaryPipeline.Completion(groundedResponse(prompt), 11L, 12L);
                }
            }, new WikiSummaryPipeline.Limits(24000, 32000, 6), store, WikiGenerationStore.BudgetLimits.defaults());
            var failure = assertThrows(WikiSummaryPipeline.CallFailure.class,
                    () -> pipeline.summarize(facts(1), Duration.ofMillis(250)));
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertEquals(WikiSummaryPipeline.FailureKind.TIMEOUT, failure.kind());
            assertEquals(4106, pipeline.progress(facts(1).period()).tokens());
            assertFalse(sessionClosed.get(), "the request lease remains alive until its late receipt settles");
            release.countDown();
            assertTrue(pipeline.awaitIdle(Duration.ofSeconds(5)));
            assertEquals(23, pipeline.progress(facts(1).period()).tokens());
            assertEquals(1, pipeline.progress(facts(1).period()).calls());
            assertTrue(sessionClosed.get());
        } finally { release.countDown(); }
    }

    @Test void tamperedCheckpointWithMatchingChecksumStillRevalidatesReferenceCatalog() throws Exception {
        Path path = temp.resolve("tampered.db");
        AtomicInteger calls = new AtomicInteger();
        try (var store = new WikiGenerationStore(path)) {
            var pipeline = pipeline(store, 24000, 12, "model", calls, WikiSummaryPipelineTest::groundedResponse);
            pipeline.summarize(facts(1), TIMEOUT);
            String original;
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path);
                 var query = connection.createStatement();
                 var rows = query.executeQuery("SELECT payload_json FROM generation_checkpoints")) {
                assertTrue(rows.next());
                original = rows.getString(1);
            }
            String corrupted = original.replace("\"f0\"", "\"unknown-fact\"");
            assertNotEquals(original, corrupted);
            String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(corrupted.getBytes(StandardCharsets.UTF_8)));
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path);
                 var update = connection.prepareStatement("UPDATE generation_checkpoints SET payload_json=?,payload_hash=?")) {
                update.setString(1, corrupted); update.setString(2, checksum); update.executeUpdate();
            }
            var failure = assertThrows(WikiSummaryPipeline.CallFailure.class, () -> pipeline.summarize(facts(1), TIMEOUT));
            assertEquals(WikiSummaryPipeline.FailureKind.LOCAL_STORAGE, failure.kind());
            assertEquals("WIKI_CHECKPOINT_INVALID", failure.code());
            assertEquals(1, calls.get());
        }
    }

    private static WikiSummaryPipeline pipeline(WikiGenerationStore store, int factChars, int periodCalls,
                                                String identity, AtomicInteger calls, Function<String, String> response) {
        return new WikiSummaryPipeline(() -> new WikiSummaryPipeline.Session() {
            public String identity() { return identity; }
            public String complete(String prompt, Duration timeout) { calls.incrementAndGet(); return response.apply(prompt); }
        }, new WikiSummaryPipeline.Limits(factChars, 32000, 6), store,
                new WikiGenerationStore.BudgetLimits(periodCalls, 256000));
    }
}
