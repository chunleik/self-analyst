package com.selfanalyst.wiki;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WikiStoreTest {

    @Test
    void generationPauseIsDurableNotDueAndDoesNotIncrementRetryCount() {
        var entry = new WikiEntry("paused", WikiLevel.HOUR, t1, t2, tz.getId(), WikiStatus.PENDING,
                null, null, List.of(), new WikiEntry.WikiMetrics(0, 0, 0, List.of(), Map.of()), List.of(),
                null, null, 0, null, null, t1, t1, null);
        store.upsert(entry);
        store.markGenerationFailure(entry, null, Map.of("state", "period_budget", "reason", "WIKI_PERIOD_BUDGET_EXHAUSTED",
                "calls", 12, "maxCalls", 12, "configurationStamp", "private-stamp"),
                Instant.parse("9999-12-31T00:00:00Z"), false);
        var stored = store.query(t1, t2, WikiLevel.HOUR).getFirst();
        assertEquals(0, stored.retryCount());
        assertEquals(1, store.findGenerationPaused().size());
        assertTrue(store.findPending(WikiLevel.HOUR, 10, t2).isEmpty());
        assertFalse(WikiGenerationProgress.from(stored).containsKey("configurationStamp"));
        assertEquals(12, WikiGenerationProgress.from(stored).get("calls"));
        store.resumeGeneration(stored);
        assertEquals(1, store.findPending(WikiLevel.HOUR, 10, t2).size());
        assertTrue(store.findGenerationPaused().isEmpty());
    }

    @TempDir
    Path tempDir;

    private WikiStore store;
    private final ZoneId tz = ZoneId.systemDefault();
    private final Instant t1 = Instant.parse("2026-06-08T10:00:00Z");
    private final Instant t2 = Instant.parse("2026-06-08T11:00:00Z");

    @BeforeEach
    void setUp() {
        store = new WikiStore(tempDir.resolve("test-wiki.db"));
    }

    @AfterEach
    void tearDown() {
        if (store != null) {
            store.close();
        }
    }

    @Test
    void shouldCreateSchema() {
        var entries = store.query(t1, t2, WikiLevel.HOUR);
        assertNotNull(entries);
        assertTrue(entries.isEmpty());
    }

    @Test
    void shouldUpsertUniquePeriod() {
        WikiEntry e1 = createPendingEntry("id-1", WikiLevel.HOUR, t1, t2);
        store.upsert(e1);
        WikiEntry e2 = createPendingEntry("id-2", WikiLevel.HOUR, t1, t2);
        store.upsert(e2);
        var results = store.query(t1, t2, WikiLevel.HOUR);
        assertEquals(1, results.size());
        assertEquals("id-2", results.get(0).id());
    }

    @Test
    void shouldTransitionStatus() {
        WikiEntry e1 = createPendingEntry("id-1", WikiLevel.HOUR, t1, t2);
        store.upsert(e1);

        store.updateStatus("id-1", WikiStatus.SUMMARIZED, "summary text", "primary task",
                List.of(), new WikiEntry.WikiMetrics(3600, 0, 5, List.of(), Map.of()),
                List.of(), "test-model", "wiki-v1");

        var results = store.query(t1, t2, WikiLevel.HOUR);
        assertEquals(WikiStatus.SUMMARIZED, results.get(0).status());
        assertEquals("summary text", results.get(0).summary());
    }

    @Test
    void shouldRoundTripJsonFields() {
        var seg = new WikiEntry.TaskSegment("coding", "wrote Java code",
                List.of("IDE visible"), List.of("IntelliJ"), "high");
        var metrics = new WikiEntry.WikiMetrics(3600, 120, 15,
                List.of(new WikiEntry.AppDuration("IntelliJ", 2400)), Map.of("focus", "high"));

        WikiEntry entry = new WikiEntry("json-test", WikiLevel.HOUR, t1, t2,
                tz.getId(), WikiStatus.PENDING, null, null, List.of(seg), metrics,
                List.of("src-1"), null, null, 0, null, null,
                Instant.now(), Instant.now(), null);

        store.upsert(entry);
        var results = store.query(t1, t2, WikiLevel.HOUR);
        assertEquals(1, results.size());
        WikiEntry loaded = results.get(0);
        assertEquals(1, loaded.taskSegments().size());
        assertEquals("coding", loaded.taskSegments().get(0).title());
        assertEquals(3600, loaded.metrics().activeSeconds());
        assertEquals("IntelliJ", loaded.metrics().topApps().get(0).app());
        assertEquals("legacy", loaded.taskSegments().getFirst().claimType());
        assertTrue(loaded.taskSegments().getFirst().evidenceFactIds().isEmpty());
    }

    @Test
    void shouldReadOldJsonWithoutInventingReferences() throws Exception {
        store.upsert(createPendingEntry("legacy-json", WikiLevel.HOUR, t1, t2));
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("test-wiki.db"));
             var statement = connection.prepareStatement("UPDATE wiki_entries SET task_segments_json=? WHERE id=?")) {
            statement.setString(1, """
                    [{"title":"旧任务","summary":"旧摘要","evidence":["旧证据"],"apps":["IDE"],"confidence":"high"}]
                    """);
            statement.setString(2, "legacy-json");
            statement.executeUpdate();
        }
        var segment = store.query(t1, t2, WikiLevel.HOUR).getFirst().taskSegments().getFirst();
        assertEquals("legacy", segment.claimType());
        assertEquals(List.of(), segment.evidenceFactIds());
        assertEquals(List.of("旧证据"), segment.evidence());
    }

    @Test
    void shouldRoundTripFactReferencesAndBoundedSourceCatalog() {
        var segment = new WikiEntry.TaskSegment("查看设计", "涉及数据库同步", List.of("标题观察"),
                List.of("IDE"), "medium", List.of("f1"), "inferred");
        var fact = new WikiTitleSampler.Fact("f1", "window", "IDE", "数据库同步设计", "window", 30.0, 1,
                List.of(new WikiTitleSampler.Interval(t1.toString(), t1.plusSeconds(30).toString(), true, List.of(7L), 0)), 0);
        store.upsert(createPendingEntry("reference-json", WikiLevel.HOUR, t1, t2));
        store.updateStatus("reference-json", WikiStatus.SUMMARIZED, "查看设计", "数据库同步", List.of(segment),
                new WikiEntry.WikiMetrics(30, 0, 0, List.of(), Map.of("evidenceFacts", List.of(fact))),
                List.of(), "test", "v7");
        var loaded = store.query(t1, t2, WikiLevel.HOUR).getFirst();
        assertEquals(segment, loaded.taskSegments().getFirst());
        var catalog = (List<?>) loaded.metrics().extra().get("evidenceFacts");
        var restoredFact = (Map<?, ?>) catalog.getFirst();
        assertEquals("f1", restoredFact.get("id"));
        assertEquals("数据库同步设计", restoredFact.get("title"));
        assertEquals(List.of(7), ((Map<?, ?>) ((List<?>) restoredFact.get("intervals")).getFirst()).get("sourceEventIds"));
    }

    @Test
    void shouldMigrateAndRoundTripBoundedProjectionCoverage() {
        WikiEntry base = createPendingEntry("coverage", WikiLevel.HOUR, t1, t2);
        WikiEntry entry = new WikiEntry(base.id(), base.level(), base.periodStart(), base.periodEnd(),
                base.timezone(), base.status(), base.summary(), base.primaryTask(),
                base.taskSegments(), base.metrics(), base.sourceEntryIds(), base.model(),
                base.promptVersion(), base.retryCount(), base.nextRetryAt(), base.lastError(),
                base.createdAt(), base.updatedAt(), base.summarizedAt(),
                "facts-v1", "projector-v2", Map.of("window",
                new WikiEntry.SourceCoverage("complete", t1, t2, 0L)));

        store.upsert(entry);
        WikiEntry loaded = store.query(t1, t2, WikiLevel.HOUR).getFirst();

        assertEquals("facts-v1", loaded.factBuilderVersion());
        assertEquals("projector-v2", loaded.projectorVersion());
        assertEquals("complete", loaded.sourceCoverage().get("window").status());
        assertThrows(IllegalArgumentException.class, () -> new WikiEntry(
                base.id(), base.level(), base.periodStart(), base.periodEnd(), base.timezone(),
                base.status(), null, null, List.of(), base.metrics(), List.of(), null, null,
                0, null, null, Instant.now(), Instant.now(), null,
                "x".repeat(65), "v1", Map.of()));
    }

    @Test
    void shouldQueryByPeriodAndLevel() {
        Instant t3 = Instant.parse("2026-06-08T12:00:00Z");
        Instant t4 = Instant.parse("2026-06-09T00:00:00Z");

        store.upsert(createPendingEntry("h1", WikiLevel.HOUR, t1, t2));
        store.upsert(createPendingEntry("h2", WikiLevel.HOUR, t2, t3));
        store.upsert(createPendingEntry("d1", WikiLevel.DAY, t1, t4));

        var hours = store.query(t1, t4, WikiLevel.HOUR);
        assertEquals(2, hours.size());

        var days = store.query(t1, t4, WikiLevel.DAY);
        assertEquals(1, days.size());
    }

    @Test
    void shouldFindPendingEntries() {
        store.upsert(createPendingEntry("p1", WikiLevel.HOUR, t1, t2));
        store.upsert(createPendingEntry("p2", WikiLevel.HOUR, t2, Instant.parse("2026-06-08T12:00:00Z")));
        store.upsert(createPendingEntry("p3", WikiLevel.DAY, t1, Instant.parse("2026-06-09T00:00:00Z")));

        var pending = store.findPending(WikiLevel.HOUR, 10);
        assertEquals(2, pending.size());
    }

    @Test
    void shouldFindFailedEntriesForRetry() {
        WikiEntry e1 = createPendingEntry("f1", WikiLevel.HOUR, t1, t2);
        store.upsert(e1);
        store.markFailed("f1", "timeout", Instant.now().minusSeconds(60));

        var retryable = store.findRetryable(10);
        assertEquals(1, retryable.size());
    }

    @Test
    void shouldCreateAndQuerySemanticDocs() {
        store.upsert(createPendingEntry("entry-x", WikiLevel.HOUR, t1, t2));
        var doc = new WikiStore.SemanticDoc("sd-1", "entry-x", "ENTRY_SUMMARY",
                "HOUR", t1.toString(), t2.toString(),
                "abc123", "text-embedding-3-small", 1536,
                WikiStore.SemanticDocStatus.PENDING, 0, null, null,
                Instant.now(), Instant.now(), null);
        store.upsertSemanticDoc(doc);

        var pending = store.findPendingSemanticDocs(5);
        assertEquals(1, pending.size());
        assertEquals("sd-1", pending.get(0).docId());

        store.markSemanticDocIndexed("sd-1");
        pending = store.findPendingSemanticDocs(5);
        assertEquals(0, pending.size());
    }

    @Test
    void shouldMarkStaleWhenModelChanges() {
        store.upsertSemanticDoc(new WikiStore.SemanticDoc("sd-2", "entry-y", "ENTRY_SUMMARY",
                "DAY", t1.toString(), t2.toString(),
                "hash1", "old-model", 1024,
                WikiStore.SemanticDocStatus.INDEXED, 0, null, null,
                Instant.now(), Instant.now(), Instant.now()));

        store.markSemanticDocsStale("new-model", 1536);
        var retryable = store.findRetryableSemanticDocs(5);
        // STALE docs are findable via findRetryable if we also check STALE...
        // Actually findRetryableSemanticDocs only checks FAILED. Let's verify via query.
        var docs = store.findSemanticDocsByEntry("entry-y");
        assertEquals(WikiStore.SemanticDocStatus.STALE, docs.get(0).status());
    }

    @Test
    void shouldFindSummarizedWithoutSemanticDocs() {
        var entry = createPendingEntry("e-no-sem", WikiLevel.HOUR, t1, t2);
        store.upsert(entry);
        store.updateStatus("e-no-sem", WikiStatus.SUMMARIZED, "summary", "task",
                List.of(), new WikiEntry.WikiMetrics(3600, 0, 0, List.of(), Map.of()),
                List.of(), "m", "v1");

        var without = store.findSummarizedWithoutSemanticDocs(10);
        assertEquals(1, without.size());
        assertEquals("e-no-sem", without.get(0).id());
    }

    private WikiEntry createPendingEntry(String id, WikiLevel level, Instant start, Instant end) {
        return new WikiEntry(id, level, start, end, tz.getId(), WikiStatus.PENDING,
                null, null, List.of(),
                new WikiEntry.WikiMetrics(0, 0, 0, List.of(), Map.of()),
                List.of(), null, null, 0, null, null,
                Instant.now(), Instant.now(), null);
    }
}
