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
