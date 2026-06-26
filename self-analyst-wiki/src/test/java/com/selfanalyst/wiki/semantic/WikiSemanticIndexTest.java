package com.selfanalyst.wiki.semantic;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.selfanalyst.wiki.WikiLevel;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class WikiSemanticIndexTest {

    @TempDir
    Path tempDir;

    private WikiSemanticIndex index;
    private final Instant t1 = Instant.parse("2026-06-01T10:00:00Z");
    private final Instant t2 = Instant.parse("2026-06-01T11:00:00Z");

    @BeforeEach
    void setUp() {
        index = new WikiSemanticIndex(tempDir.resolve("lucene"), 4);
    }

    @AfterEach
    void tearDown() {
        if (index != null) index.close();
    }

    @Test
    void shouldIndexAndSearch() throws Exception {
        float[] vec1 = {1.0f, 0.0f, 0.0f, 0.0f};
        float[] vec2 = {0.0f, 1.0f, 0.0f, 0.0f};

        index.indexDocument("doc-1", "entry-1", "ENTRY_SUMMARY", WikiLevel.HOUR,
                t1, t2, "coding Java", "summary text", "Java coding",
                "matched text for doc1", vec1);
        index.indexDocument("doc-2", "entry-2", "TASK_SEGMENT", WikiLevel.DAY,
                t1, Instant.parse("2026-06-02T00:00:00Z"), "meeting notes",
                "meeting summary", "team meeting",
                "matched text for doc2", vec2);

        // Search with vec1-like query → doc-1 should score highest
        float[] queryVec = {0.9f, 0.1f, 0.0f, 0.0f};
        List<WikiSemanticIndex.SearchHit> hits = index.search(queryVec, 5, null, null, null);
        assertEquals(2, hits.size());
        assertTrue(hits.get(0).score() > hits.get(1).score());
        assertEquals("entry-1", hits.get(0).entryId());
        assertEquals("Java coding", hits.get(0).primaryTask());
    }

    @Test
    void shouldFilterByLevel() throws Exception {
        float[] vec = {0.0f, 0.0f, 1.0f, 0.0f};
        index.indexDocument("doc-3", "entry-3", "ENTRY_SUMMARY", WikiLevel.HOUR,
                t1, t2, "text", "summary", "task", "match", vec);
        index.indexDocument("doc-4", "entry-4", "ENTRY_SUMMARY", WikiLevel.DAY,
                t1, Instant.parse("2026-06-02T00:00:00Z"), "text", "summary", "task", "match", vec);

        List<WikiSemanticIndex.SearchHit> hits = index.search(vec, 5, null, null, WikiLevel.HOUR);
        assertEquals(1, hits.size());
        assertEquals("entry-3", hits.get(0).entryId());
    }

    @Test
    void shouldFilterByTimeRange() throws Exception {
        float[] vec = {0.0f, 0.0f, 0.0f, 1.0f};
        Instant earlyStart = Instant.parse("2026-05-01T00:00:00Z");
        Instant earlyEnd = Instant.parse("2026-05-02T00:00:00Z");

        index.indexDocument("doc-5", "entry-5", "ENTRY_SUMMARY", WikiLevel.DAY,
                earlyStart, earlyEnd, "early", "early summary", "early task", "early", vec);
        index.indexDocument("doc-6", "entry-6", "ENTRY_SUMMARY", WikiLevel.DAY,
                t1, t2, "later", "later summary", "later task", "later", vec);

        // Filter to June 2026
        List<WikiSemanticIndex.SearchHit> hits = index.search(vec, 5, t1,
                Instant.parse("2026-07-01T00:00:00Z"), null);
        assertEquals(1, hits.size());
        assertEquals("entry-6", hits.get(0).entryId());
    }

    @Test
    void shouldDeleteByEntry() throws Exception {
        float[] vec = {1.0f, 1.0f, 0.0f, 0.0f};
        index.indexDocument("doc-7", "entry-del", "ENTRY_SUMMARY", WikiLevel.HOUR,
                t1, t2, "delete me", "summary", "task", "match", vec);
        index.deleteByEntry("entry-del");

        List<WikiSemanticIndex.SearchHit> hits = index.search(vec, 5, null, null, null);
        assertEquals(0, hits.size());
    }

    @Test
    void shouldReturnStoredFields() throws Exception {
        float[] vec = {0.5f, 0.5f, 0.5f, 0.5f};
        index.indexDocument("doc-8", "entry-8", "TASK_SEGMENT", WikiLevel.WEEK,
                t1, Instant.parse("2026-06-08T00:00:00Z"),
                "index text", "the summary", "the primary task",
                "matched snippet", vec);

        List<WikiSemanticIndex.SearchHit> hits = index.search(vec, 1, null, null, null);
        assertEquals(1, hits.size());
        WikiSemanticIndex.SearchHit hit = hits.get(0);
        assertEquals("TASK_SEGMENT", hit.docType());
        assertEquals("WEEK", hit.level());
        assertEquals("the summary", hit.summary());
        assertEquals("the primary task", hit.primaryTask());
        assertEquals("matched snippet", hit.matchedText());
    }
}
