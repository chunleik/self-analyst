package com.selfanalyst.wiki;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

class WikiGenerationStoreTest {
    @TempDir Path directory;
    private static final Instant NOW = Instant.parse("2026-09-22T04:00:00Z");
    private static final WikiPeriod PERIOD = new WikiPeriod(WikiLevel.DAY, NOW.minusSeconds(86400), NOW, "Asia/Shanghai");
    private static final String KEY = WikiGenerationStore.periodKey(PERIOD);
    private static final WikiGenerationStore.BudgetLimits DEFAULTS = WikiGenerationStore.BudgetLimits.defaults();
    private static final String PAYLOAD = "{\"schemaVersion\":1,\"summary\":\"查看设计\",\"taskSegments\":[]}";

    private Path database() { return directory.resolve("wiki-generation.db"); }
    private static Clock at(Instant time) { return Clock.fixed(time, ZoneOffset.UTC); }

    @Test
    void uncertainReservationSurvivesRestartDateInputAndModelChanges() {
        String callId;
        try (var store = new WikiGenerationStore(database(), at(NOW))) {
            callId = store.reserve(KEY, "input-a:model-a", "leaf-1", 1000, DEFAULTS).callId();
            assertEquals(5096, store.snapshot(KEY).tokens());
        }
        try (var store = new WikiGenerationStore(database(), at(NOW.plus(Duration.ofDays(2))))) {
            assertEquals(1, store.snapshot(KEY).calls());
            assertEquals(1, store.snapshot(KEY).unsettledCalls());
            assertEquals(5096, store.snapshot(KEY).reservedTokens());
            var second = store.reserve(KEY, "input-b:model-b", "different-node", 2000, DEFAULTS);
            assertNotEquals(callId, second.callId());
            assertEquals(2, store.snapshot(KEY).calls());
            assertEquals(11192, store.snapshot(KEY).tokens());
            store.settle(callId, 50L, 100L);
            assertEquals(6246, store.snapshot(KEY).tokens());
            assertEquals(1, store.snapshot(KEY).unsettledCalls());
        }
    }

    @Test
    void realUsageReplacesReserveOnceEvenWhenItExceedsBudget() {
        var limits = new WikiGenerationStore.BudgetLimits(3, 6000);
        String callId;
        try (var store = new WikiGenerationStore(database())) {
            callId = store.reserve(KEY, "generation", "node", 100, limits).callId();
            store.settle(callId, 3000L, 9000L);
            store.settle(callId, 3000L, 9000L);
            store.settle(callId, 1L, 1L);
            store.settle(callId, null, null);
            assertEquals(12000, store.snapshot(KEY).tokens());
            assertEquals(1, store.snapshot(KEY).calls());
            assertEquals(0, store.snapshot(KEY).reservedTokens());
            assertEquals(0, store.snapshot(KEY).unsettledCalls());
            assertThrows(WikiPeriodBudgetException.class, () -> store.reserve(KEY, "generation", "later", 0, limits));
        }
        try (var store = new WikiGenerationStore(database())) {
            store.settle(callId, 3000L, 9000L);
            assertEquals(12000, store.snapshot(KEY).tokens());
        }
    }

    @Test
    void conservativeSettlementCanUpgradeToActualButNeverReturnsUnknownQuota() {
        try (var store = new WikiGenerationStore(database())) {
            var call = store.reserve(KEY, "generation", "node", 100, DEFAULTS);
            store.settle(call.callId(), null, null);
            store.settle(call.callId(), null, null);
            assertEquals(4196, store.snapshot(KEY).tokens());
            assertEquals(4196, store.snapshot(KEY).reservedTokens());
            assertEquals(1, store.snapshot(KEY).estimatedCalls());
            assertEquals(0, store.snapshot(KEY).unsettledCalls());
            store.settle(call.callId(), 10L, 20L);
            store.settle(call.callId(), 10L, 20L);
            assertThrows(IllegalStateException.class, () -> store.cancelBeforeSend(call.callId()));
            assertEquals(30, store.snapshot(KEY).tokens());
            assertEquals(0, store.snapshot(KEY).estimatedCalls());
            assertEquals(0, store.snapshot(KEY).reservedTokens());
        }
    }

    @Test
    void onlyExplicitPreSendCancellationReleasesReservationIdempotently() throws Exception {
        String callId;
        try (var store = new WikiGenerationStore(database())) {
            callId = store.reserve(KEY, "generation", "node", 100, DEFAULTS).callId();
            store.cancelBeforeSend(callId);
            store.cancelBeforeSend(callId);
            assertEquals(0, store.snapshot(KEY).calls());
            assertEquals(0, store.snapshot(KEY).tokens());
            assertThrows(IllegalStateException.class, () -> store.settle(callId, 0L, 0L));
        }
        WikiGenerationStore.verifyReadOnly(database());
        try (var store = new WikiGenerationStore(database())) {
            store.cancelBeforeSend(callId);
            assertEquals(0, store.snapshot(KEY).tokens());
        }
    }

    @Test
    void lateConfirmedPreSendRejectionCanReleaseTimeoutEstimate() throws Exception {
        try (var store = new WikiGenerationStore(database())) {
            var call = store.reserve(KEY, "generation", "node", 100, DEFAULTS);
            store.settle(call.callId(), null, null);
            assertEquals(4196, store.snapshot(KEY).tokens());
            // 请求线程随后确认尚未发送，只有此证据才能释放先前未知的预留。
            store.cancelBeforeSend(call.callId());
            store.settle(call.callId(), null, null);
            store.cancelBeforeSend(call.callId());
            assertEquals(0, store.snapshot(KEY).calls());
            assertEquals(0, store.snapshot(KEY).tokens());
            assertEquals(0, store.snapshot(KEY).unsettledCalls());
            assertEquals(0, store.snapshot(KEY).estimatedCalls());
        }
        WikiGenerationStore.verifyReadOnly(database());
    }

    @Test
    void differentConnectionsCannotOverAdmitCallsOrTokens() throws Exception {
        var connections = new ArrayList<WikiGenerationStore>();
        for (int index = 0; index < 8; index++) connections.add(new WikiGenerationStore(database()));
        try (var executor = Executors.newFixedThreadPool(8)) {
            for (var limits : List.of(new WikiGenerationStore.BudgetLimits(3, 1000000),
                    new WikiGenerationStore.BudgetLimits(20, 3 * 5096))) {
                String periodKey = WikiGenerationStore.periodKey(new WikiPeriod(WikiLevel.HOUR,
                        NOW.minusSeconds(limits.maxCalls()), NOW, "UTC"));
                CountDownLatch start = new CountDownLatch(1);
                List<Future<Boolean>> results = new ArrayList<>();
                for (int index = 0; index < connections.size(); index++) {
                    var store = connections.get(index);
                    String generation = periodKey + ":" + index;
                    results.add(executor.submit(() -> {
                        start.await();
                        try { store.reserve(periodKey, generation, "node", 1000, limits); return true; }
                        catch (WikiPeriodBudgetException expected) { return false; }
                    }));
                }
                start.countDown();
                int accepted = 0;
                for (Future<Boolean> result : results) if (result.get()) accepted++;
                assertEquals(3, accepted);
                assertEquals(3, connections.getFirst().snapshot(periodKey).calls());
                assertEquals(3 * 5096, connections.getLast().snapshot(periodKey).tokens());
            }
        } finally {
            connections.forEach(WikiGenerationStore::close);
        }
        WikiGenerationStore.verifyReadOnly(database());
    }

    @Test
    void rejectedAdmissionSurvivesRestartAndOnlySufficientExplicitIncreaseResumes() {
        var initial = new WikiGenerationStore.BudgetLimits(2, 7000);
        try (var store = new WikiGenerationStore(database())) {
            store.reserve(KEY, "first", "leaf", 100, initial);
            var error = assertThrows(WikiPeriodBudgetException.class,
                    () -> store.reserve(KEY, "second", "leaf", 1000, initial));
            assertEquals("period_budget_exhausted", error.code());
            assertEquals(1, error.snapshot().calls());
            assertEquals(5096, error.snapshot().requiredTokens());
            assertFalse(store.canResume(KEY, initial));
        }
        try (var store = new WikiGenerationStore(database(), at(NOW.plus(Duration.ofDays(30))))) {
            assertFalse(store.canResume(KEY, new WikiGenerationStore.BudgetLimits(20, 7000)));
            assertTrue(store.canResume(KEY, new WikiGenerationStore.BudgetLimits(2, 10000)));
            store.reserve(KEY, "second", "leaf", 1000, new WikiGenerationStore.BudgetLimits(2, 10000));
            assertEquals(2, store.snapshot(KEY).calls());
            assertEquals(9292, store.snapshot(KEY).tokens());
            assertEquals(0, store.snapshot(KEY).requiredTokens());
        }
    }

    @Test
    void leavesAndFinalSurviveRestartWhileIncompatibleGenerationCannotReuseThem() throws Exception {
        try (var store = new WikiGenerationStore(database())) {
            var leaf = store.reserve(KEY, "same-input-model", "leaf", 100, DEFAULTS);
            store.completeCheckpoint(leaf.callId(), "same-input-model", "leaf", "LEAF", PAYLOAD, 20L, 30L);
            var root = store.reserve(KEY, "same-input-model", "root", 100, DEFAULTS);
            store.completeCheckpoint(root.callId(), "same-input-model", "root", "FINAL", PAYLOAD, 30L, 40L);
            store.completeCheckpoint(root.callId(), "same-input-model", "root", "FINAL", PAYLOAD, 30L, 40L);
        }
        WikiGenerationStore.verifyReadOnly(database());
        try (var store = new WikiGenerationStore(database())) {
            assertEquals("LEAF", store.loadCheckpoint("same-input-model", "leaf").orElseThrow().stage());
            assertEquals(PAYLOAD, store.loadCheckpoint("same-input-model", "root").orElseThrow().payloadJson());
            assertTrue(store.loadCheckpoint("different-input", "root").isEmpty());
            assertTrue(store.loadCheckpoint("different-model", "leaf").isEmpty());
            assertEquals(120, store.snapshot(KEY).tokens());
            assertEquals(2, store.snapshot(KEY).calls());
            store.markPublished("same-input-model");
        }
    }

    @Test
    void topicCheckpointV2CoexistsWithLegacyPayloadWithoutChangingDatabaseSchema() throws Exception {
        String topicPayload = """
                {"schemaVersion":2,"summary":"查看设计","primaryTask":"设计资料",
                 "topicCards":[{"id":"t-example","title":"设计资料","summary":"涉及设计资料。",
                   "memberInputIds":["f1","f2"],"representativeFactIds":["f1"],"sourceTopicIds":[],
                   "claimType":"inferred","confidence":"medium"}],
                 "unresolvedInputIds":[],"unresolvedTopicIds":[],"taskSegments":[]}
                """;
        try (var store = new WikiGenerationStore(database())) {
            var legacy = store.reserve(KEY, "legacy-v1", "root", 100, DEFAULTS);
            store.completeCheckpoint(legacy.callId(), "legacy-v1", "root", "FINAL", PAYLOAD, 10L, 20L);
            var topic = store.reserve(KEY, "topics-v2", "root", 100, DEFAULTS);
            store.completeCheckpoint(topic.callId(), "topics-v2", "root", "FINAL", topicPayload, 20L, 30L);
        }
        WikiGenerationStore.verifyReadOnly(database());
        try (var store = new WikiGenerationStore(database())) {
            assertEquals(PAYLOAD, store.loadCheckpoint("legacy-v1", "root").orElseThrow().payloadJson());
            assertEquals(topicPayload, store.loadCheckpoint("topics-v2", "root").orElseThrow().payloadJson());
            assertEquals(2, store.snapshot(KEY).calls());
            assertEquals(80, store.snapshot(KEY).tokens());
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database());
             var query = connection.createStatement(); var rows = query.executeQuery("PRAGMA user_version")) {
            assertTrue(rows.next());
            assertEquals(1, rows.getInt(1), "载荷版本变化不应迁移独立账本的表结构");
        }
    }

    @Test
    void checkpointConflictRollsBackRealUsageSettlement() {
        try (var store = new WikiGenerationStore(database())) {
            var first = store.reserve(KEY, "generation", "node", 100, DEFAULTS);
            store.completeCheckpoint(first.callId(), "generation", "node", "FINAL", PAYLOAD, 10L, 20L);
            var second = store.reserve(KEY, "generation", "node", 200, DEFAULTS);
            assertThrows(IllegalStateException.class, () -> store.completeCheckpoint(second.callId(), "generation",
                    "node", "FINAL", "{\"schemaVersion\":1,\"summary\":\"different\"}", 50L, 50L));
            assertEquals(4326, store.snapshot(KEY).tokens());
            assertEquals(1, store.snapshot(KEY).unsettledCalls());
            assertEquals(PAYLOAD, store.loadCheckpoint("generation", "node").orElseThrow().payloadJson());
            assertThrows(IllegalArgumentException.class, () -> store.completeCheckpoint(second.callId(), "generation",
                    "other-node", "FINAL", PAYLOAD, 50L, 50L));
        }
    }

    @Test
    void malformedUnsafeAndOversizedPayloadsNeverBecomeSuccessfulCheckpoints() {
        try (var store = new WikiGenerationStore(database())) {
            var call = store.reserve(KEY, "generation", "node", 100, DEFAULTS);
            for (String invalid : List.of("not json", "[]", "null", "{\"schemaVersion\":3}",
                    "{\"schemaVersion\":18446744073709551617}", PAYLOAD + "{}",
                    "{\"schemaVersion\":1,\"schemaVersion\":1}", "{\"schemaVersion\":1,\"prompt\":\"private\"}",
                    "{\"schemaVersion\":1,\"nested\":{\"api_key\":\"private\"}}",
                    "{\"schemaVersion\":1,\"summary\":\"" + "中".repeat(180000) + "\"}")) {
                assertThrows(IllegalArgumentException.class, () -> store.completeCheckpoint(call.callId(), "generation",
                        "node", "FINAL", invalid, 10L, 20L));
            }
            assertThrows(IllegalArgumentException.class, () -> store.completeCheckpoint(call.callId(), "generation",
                    "node", "FAILED", PAYLOAD, 10L, 20L));
            assertTrue(store.loadCheckpoint("generation", "node").isEmpty());
            assertEquals(4196, store.snapshot(KEY).tokens());
            assertEquals(1, store.snapshot(KEY).unsettledCalls());
        }
    }

    @Test
    void cleanupOnlyRemovesPublishedCheckpointsAndRetainsPermanentBudgetAndIdempotency() throws Exception {
        String completedCall;
        try (var store = new WikiGenerationStore(database(), at(NOW))) {
            completedCall = store.reserve(KEY, "completed", "root", 100, DEFAULTS).callId();
            store.completeCheckpoint(completedCall, "completed", "root", "DIRECT", PAYLOAD, 10L, 20L);
            store.markPublished("completed");
            var unfinished = store.reserve(KEY, "unfinished", "root", 100, DEFAULTS);
            store.completeCheckpoint(unfinished.callId(), "unfinished", "root", "FINAL", PAYLOAD, null, null);
            var leaf = store.reserve(KEY, "in-progress", "leaf", 100, DEFAULTS);
            store.completeCheckpoint(leaf.callId(), "in-progress", "leaf", "LEAF", PAYLOAD, 40L, 50L);
            assertThrows(IllegalStateException.class, () -> store.markPublished("in-progress"));
        }
        try (var store = new WikiGenerationStore(database(), at(NOW.plus(Duration.ofDays(6))))) {
            assertEquals(0, store.cleanupPublished());
        }
        try (var store = new WikiGenerationStore(database(), at(NOW.plus(Duration.ofDays(8))))) {
            assertEquals(1, store.cleanupPublished());
            assertTrue(store.loadCheckpoint("completed", "root").isEmpty());
            assertTrue(store.loadCheckpoint("unfinished", "root").isPresent());
            assertTrue(store.loadCheckpoint("in-progress", "leaf").isPresent());
            assertEquals(3, store.snapshot(KEY).calls());
            assertEquals(4316, store.snapshot(KEY).tokens());
            store.settle(completedCall, 10L, 20L);
            assertEquals(4316, store.snapshot(KEY).tokens());
        }
        WikiGenerationStore.verifyReadOnly(database());
    }

    @Test
    void readOnlyVerificationRejectsUnknownOrCorruptStorageWithoutReplacingIt() throws Exception {
        WikiGenerationStore.verifyReadOnly(database());
        assertFalse(Files.exists(database()));
        try (var ignored = new WikiGenerationStore(database())) { }
        byte[] good = Files.readAllBytes(database());
        WikiGenerationStore.verifyReadOnly(database());
        assertArrayEquals(good, Files.readAllBytes(database()));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database()); var sql = connection.createStatement()) {
            sql.execute("PRAGMA user_version=2");
        }
        byte[] futureVersion = Files.readAllBytes(database());
        assertThrows(IOException.class, () -> WikiGenerationStore.verifyReadOnly(database()));
        assertThrows(IllegalStateException.class, () -> new WikiGenerationStore(database()));
        assertArrayEquals(futureVersion, Files.readAllBytes(database()));

        Path corrupt = directory.resolve("corrupt.db");
        Files.writeString(corrupt, "not a sqlite database");
        byte[] corruptBytes = Files.readAllBytes(corrupt);
        assertThrows(IOException.class, () -> WikiGenerationStore.verifyReadOnly(corrupt));
        assertThrows(IllegalStateException.class, () -> new WikiGenerationStore(corrupt));
        assertArrayEquals(corruptBytes, Files.readAllBytes(corrupt));
    }

    @Test
    void checkpointHashAndLedgerMismatchAreRejected() throws Exception {
        try (var store = new WikiGenerationStore(database())) {
            var call = store.reserve(KEY, "generation", "root", 100, DEFAULTS);
            store.completeCheckpoint(call.callId(), "generation", "root", "FINAL", PAYLOAD, 1L, 2L);
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database()); var sql = connection.createStatement()) {
            sql.execute("UPDATE generation_checkpoints SET payload_json='{}'");
        }
        assertThrows(IOException.class, () -> WikiGenerationStore.verifyReadOnly(database()));
        assertThrows(IllegalStateException.class, () -> new WikiGenerationStore(database()));

        Path altered = directory.resolve("altered.db");
        try (var store = new WikiGenerationStore(altered)) { store.reserve(KEY, "generation", "root", 100, DEFAULTS); }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + altered); var sql = connection.createStatement()) {
            sql.execute("UPDATE generation_periods SET tokens=0");
        }
        assertThrows(IOException.class, () -> WikiGenerationStore.verifyReadOnly(altered));
        assertThrows(IllegalStateException.class, () -> new WikiGenerationStore(altered));
    }

    @Test
    void customOutputReservationAndPeriodIdentityRemainSeparate() {
        try (var store = WikiGenerationStore.inMemory()) {
            var custom = new WikiGenerationStore.BudgetLimits(12, 256000, 1234);
            assertEquals(1334, store.reserve(KEY, "generation", "node", 100, custom).reservedTokens());
            assertEquals(KEY, WikiGenerationStore.periodKey(new WikiPeriod(PERIOD.level(), PERIOD.start(), PERIOD.end(), PERIOD.timezone())));
            assertNotEquals(KEY, WikiGenerationStore.periodKey(new WikiPeriod(WikiLevel.HOUR, PERIOD.start(), PERIOD.end(), PERIOD.timezone())));
            assertNotEquals(KEY, WikiGenerationStore.periodKey(new WikiPeriod(PERIOD.level(), PERIOD.start(), PERIOD.end(), "UTC")));
            assertNotEquals(KEY, WikiGenerationStore.periodKey(new WikiPeriod(PERIOD.level(), PERIOD.start().minusSeconds(1), PERIOD.end(), PERIOD.timezone())));
            assertNotEquals(KEY, WikiGenerationStore.periodKey(new WikiPeriod(PERIOD.level(), PERIOD.start(), PERIOD.end().plusSeconds(1), PERIOD.timezone())));
            assertThrows(IllegalArgumentException.class, () -> store.reserve(KEY, "bad", "node", Long.MAX_VALUE, DEFAULTS));
        }
    }
}
