package com.selfanalyst.wiki.semantic;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.selfanalyst.wiki.WikiEntry;
import com.selfanalyst.wiki.WikiLevel;
import com.selfanalyst.wiki.WikiStatus;
import com.selfanalyst.wiki.WikiStore;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WikiEmbeddingWorkerTest {

    @TempDir
    Path tempDir;

    private WikiStore store;
    private final ZoneId tz = ZoneId.systemDefault();
    private final Instant t1 = Instant.parse("2026-06-01T10:00:00Z");
    private final Instant t2 = Instant.parse("2026-06-01T11:00:00Z");

    @BeforeEach
    void setUp() {
        store = new WikiStore(tempDir.resolve("test-wiki.db"));
    }

    @AfterEach
    void tearDown() {
        if (store != null) store.close();
    }

    @Test
    void shouldEnqueueSemanticDocsForSummarizedEntry() {
        WikiEntry entry = new WikiEntry("test-entry", WikiLevel.HOUR, t1, t2, tz.getId(),
                WikiStatus.SUMMARIZED, "写了Java代码", "SelfAnalyst开发",
                List.of(new WikiEntry.TaskSegment("编码", "写WikiStore",
                        List.of("IDE可见"), List.of("IntelliJ"), "high")),
                new WikiEntry.WikiMetrics(3600, 0, 5, List.of(), Map.of()),
                List.of(), "test-model", "wiki-v1", 0, null, null,
                Instant.now(), Instant.now(), Instant.now());

        store.upsert(entry);

        // Simulate what WikiWorker does after summarization
        WikiEmbeddingWorker worker = new WikiEmbeddingWorker(store, null, null,
                "text-embedding-3-small", 1536, 60);
        worker.enqueueEntry(entry);

        // Should have 2 semantic docs: 1 ENTRY_SUMMARY + 1 TASK_SEGMENT
        List<WikiStore.SemanticDoc> docs = store.findPendingSemanticDocs(10);
        assertEquals(2, docs.size());
        assertEquals("ENTRY_SUMMARY", docs.get(0).docType());
        assertEquals("TASK_SEGMENT", docs.get(1).docType());
        assertEquals("test-entry", docs.get(0).entryId());
    }

    @Test
    void shouldBuildEntryIndexText() {
        WikiEntry entry = new WikiEntry("e1", WikiLevel.DAY, t1, t2, "Asia/Shanghai",
                WikiStatus.SUMMARIZED, "整体写代码", "开发功能",
                List.of(new WikiEntry.TaskSegment("编码", "写代码", List.of(), List.of(), "high"),
                        new WikiEntry.TaskSegment("开会", "讨论需求", List.of(), List.of(), "medium")),
                new WikiEntry.WikiMetrics(7200, 0, 10, List.of(), Map.of()),
                List.of(), "m", "v1", 0, null, null, Instant.now(), Instant.now(), Instant.now());

        String text = WikiEmbeddingWorker.buildEntryIndexText(entry,
                ZoneId.of("Asia/Shanghai"),
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        assertTrue(text.contains("整体写代码"));
        assertTrue(text.contains("开发功能"));
        assertTrue(text.contains("DAY"));
        assertTrue(text.contains("编码"));
        assertTrue(text.contains("开会"));
    }

    @Test
    void shouldBuildSegmentIndexText() {
        WikiEntry.TaskSegment seg = new WikiEntry.TaskSegment(
                "写WikiStore", "实现了数据库层", List.of("IDE"), List.of("IntelliJ"), "high");
        String text = WikiEmbeddingWorker.buildSegmentIndexText(seg);
        assertTrue(text.contains("写WikiStore"));
        assertTrue(text.contains("实现了数据库层"));
        assertTrue(text.contains("high"));
        assertTrue(text.contains("IntelliJ"));
    }

    @Test
    void shouldNotContainOriginalContent() {
        // Privacy test: segment text must not include evidence raw content
        WikiEntry.TaskSegment seg = new WikiEntry.TaskSegment(
                "测试", "测试摘要",
                List.of("some raw OCR text that should not appear as-is"),
                List.of("App"), "high");
        String text = WikiEmbeddingWorker.buildSegmentIndexText(seg);
        // Evidence is NOT included in the index text
        assertFalse(text.contains("raw OCR text"));
    }
}
