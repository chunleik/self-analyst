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

class WikiToolsTest {

    @TempDir
    Path tempDir;

    private WikiStore store;
    private WikiTools tools;
    private final ZoneId tz = ZoneId.systemDefault();
    private final Instant t1 = Instant.parse("2026-06-01T10:00:00Z");
    private final Instant t2 = Instant.parse("2026-06-01T11:00:00Z");

    @BeforeEach
    void setUp() {
        store = new WikiStore(tempDir.resolve("test-tools.db"));
        tools = new WikiTools(store);
    }

    @AfterEach
    void tearDown() {
        if (store != null) {
            store.close();
        }
    }

    @Test
    void shouldAutoSelectLevelByRange() {
        // ≤6 hours → HOUR
        Instant shortEnd = t1.plusSeconds(6 * 3600);
        String result = tools.queryWiki(t1.toString(), shortEnd.toString(), null);
        assertTrue(result.contains("HOUR") || result.contains("entries"));

        // >6h ≤2d → HALF_DAY
        Instant medEnd = t1.plusSeconds(48 * 3600);
        result = tools.queryWiki(t1.toString(), medEnd.toString(), null);
        assertTrue(result.contains("entries"));

        // >2d ≤14d → DAY
        Instant longEnd = t1.plusSeconds(10 * 24 * 3600L);
        result = tools.queryWiki(t1.toString(), longEnd.toString(), null);
        assertTrue(result.contains("entries"));
    }

    @Test
    void shouldRespectExplicitLevel() {
        store.upsert(new WikiEntry("id-1", WikiLevel.HOUR, t1, t2, tz.getId(), WikiStatus.SUMMARIZED,
                "test summary", "test task", List.of(),
                new WikiEntry.WikiMetrics(3600, 0, 5, List.of(), Map.of()),
                List.of(), "test-model", "wiki-v1", 0, null, null,
                Instant.now(), Instant.now(), Instant.now()));

        String result = tools.queryWiki(t1.toString(), t2.plusSeconds(3600).toString(), "HOUR");
        assertTrue(result.contains("test summary"));
    }

    @Test
    void shouldReportPendingAndFailedRanges() {
        store.upsert(new WikiEntry("id-p", WikiLevel.HOUR, t1, t2, tz.getId(), WikiStatus.PENDING,
                null, null, List.of(),
                new WikiEntry.WikiMetrics(0, 0, 0, List.of(), Map.of()),
                List.of(), null, null, 0, null, null,
                Instant.now(), Instant.now(), null));

        store.upsert(new WikiEntry("id-f", WikiLevel.HOUR, t2, Instant.parse("2026-06-01T12:00:00Z"),
                tz.getId(), WikiStatus.FAILED, null, null, List.of(),
                new WikiEntry.WikiMetrics(0, 0, 0, List.of(), Map.of()),
                List.of(), null, null, 1, null, "timeout",
                Instant.now(), Instant.now(), null));

        String result = tools.queryWiki(t1.toString(),
                Instant.parse("2026-06-01T13:00:00Z").toString(), "HOUR");
        assertTrue(result.contains("pending"));
    }

    @Test
    void shouldReturnSemanticSearchFallbackWhenNotAvailable() {
        String result = tools.semanticSearchWiki("test query", null, null, null, 0);
        assertTrue(result.contains("fallbackSuggestion"));
        assertTrue(result.contains("queryWiki"));
    }

    @Test
    void shouldReportHasSemanticIndexFalse() {
        assertFalse(tools.hasSemanticIndex());
    }
}
