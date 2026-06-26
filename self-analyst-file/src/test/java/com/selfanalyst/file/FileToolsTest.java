package com.selfanalyst.file;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FileToolsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private FileWatchStore store;
    private FileTools tools;

    @BeforeEach
    void setUp(@TempDir Path tmp) {
        store = new FileWatchStore(tmp.resolve("file-watch.db"));
        store.upsertPending("/a/doc.md", "doc.md", "/a", "md");
        store.updateIndexed("/a/doc.md", 100, Instant.parse("2026-06-18T00:00:00Z"),
                "h", "this is the summary", List.of("alpha", "beta"), "llm", "file-v1");
        tools = new FileTools(store); // no semantic index / embedding
    }

    @AfterEach
    void tearDown() {
        if (store != null) store.close();
    }

    @Test
    void searchFilesWithoutEmbeddingDegradesGracefully() throws Exception {
        String json = tools.searchFiles("anything", null, null, null, null, 5);
        Map<?, ?> out = MAPPER.readValue(json, Map.class);
        assertFalse(out.containsKey("error"), "must not throw/error, degrades instead");
        assertTrue(out.containsKey("results"));
        assertTrue(out.containsKey("note"), "fallback note present");
        List<?> results = (List<?>) out.get("results");
        assertEquals(1, results.size());
    }

    @Test
    void getFileSummaryReturnsRecord() throws Exception {
        String json = tools.getFileSummary("/a/doc.md");
        Map<?, ?> out = MAPPER.readValue(json, Map.class);
        assertEquals("this is the summary", out.get("summary"));
        assertEquals("INDEXED", out.get("status"));
        assertEquals(List.of("alpha", "beta"), out.get("mainTopics"));
    }

    @Test
    void getFileSummaryUnknownPath() throws Exception {
        String json = tools.getFileSummary("/nope.txt");
        Map<?, ?> out = MAPPER.readValue(json, Map.class);
        assertTrue(out.containsKey("error"));
    }

    @Test
    void listRecentFilesReturnsIndexed() throws Exception {
        String json = tools.listRecentFiles(null, null, null, 10);
        Map<?, ?> out = MAPPER.readValue(json, Map.class);
        List<?> results = (List<?>) out.get("results");
        assertEquals(1, results.size());
    }

    @Test
    void fileIndexStatusCounts() throws Exception {
        String json = tools.fileIndexStatus();
        Map<?, ?> out = MAPPER.readValue(json, Map.class);
        Map<?, ?> a = (Map<?, ?>) out.get("/a");
        assertNotNull(a);
        assertEquals(1, ((Number) a.get("INDEXED")).intValue());
    }
}
