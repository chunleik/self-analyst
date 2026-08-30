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
    private Path root;
    private String trackedPath;

    @BeforeEach
    void setUp(@TempDir Path tmp) {
        store = new FileWatchStore(tmp.resolve("file-watch.db"));
        root = tmp.resolve("a").toAbsolutePath().normalize();
        trackedPath = root.resolve("docs/design.md").toString();
        store.upsertPending(trackedPath, "docs/design.md", root.toString(), "md");
        store.updateCollected(trackedPath, 100,
                Instant.parse("2026-06-17T00:00:00Z"),
                Instant.parse("2026-06-18T00:00:00Z"));
        tools = new FileTools(store);
        tools.updateWatchRoots(List.of(root));
    }

    @AfterEach
    void tearDown() {
        if (store != null) store.close();
    }

    @Test
    void searchFilesUsesLocalPathMetadata() throws Exception {
        Map<?, ?> out = MAPPER.readValue(
                tools.searchFiles("design", null, "md", null, null, 5), Map.class);
        assertFalse(out.containsKey("error"));
        List<?> results = (List<?>) out.get("results");
        assertEquals(1, results.size());
        Map<?, ?> result = (Map<?, ?>) results.getFirst();
        assertEquals("design.md", result.get("name"));
        assertFalse(result.containsKey("summary"));
        assertFalse(result.containsKey("mainTopics"));
    }

    @Test
    void getFileMetadataReturnsOnlyAllowedFields() throws Exception {
        Map<?, ?> out = MAPPER.readValue(
                tools.getFileMetadata(trackedPath), Map.class);
        assertEquals("design.md", out.get("name"));
        assertEquals("COLLECTED", out.get("status"));
        assertEquals(100, ((Number) out.get("sizeBytes")).intValue());
        assertEquals("2026-06-17T00:00:00Z", out.get("fileCreatedAt"));
        assertEquals("2026-06-18T00:00:00Z", out.get("lastModified"));
        for (String forbidden : List.of("summary", "mainTopics", "fileHash", "model", "promptVersion")) {
            assertFalse(out.containsKey(forbidden));
        }
    }

    @Test
    void getFileMetadataUnknownPath() throws Exception {
        Map<?, ?> out = MAPPER.readValue(tools.getFileMetadata("/nope.txt"), Map.class);
        assertTrue(out.containsKey("error"));
    }

    @Test
    void listRecentFilesReturnsCollected() throws Exception {
        Map<?, ?> out = MAPPER.readValue(
                tools.listRecentFiles(null, null, null, 10), Map.class);
        assertEquals(1, ((List<?>) out.get("results")).size());
    }

    @Test
    void fileCollectionStatusCounts() throws Exception {
        Map<?, ?> out = MAPPER.readValue(tools.fileCollectionStatus(), Map.class);
        Map<?, ?> counts = (Map<?, ?>) out.get(root.toString());
        assertNotNull(counts);
        assertEquals(1, ((Number) counts.get("COLLECTED")).intValue());
    }

    @Test
    void removedRootsAreHiddenFromEveryAgentTool(@TempDir Path tmp) throws Exception {
        Path removedRoot = tmp.resolve("removed").toAbsolutePath().normalize();
        String removedPath = removedRoot.resolve("private.txt").toString();
        store.upsertPending(removedPath, "private.txt", removedRoot.toString(), "txt");
        store.updateCollected(removedPath, 7, Instant.now(), Instant.now());

        tools.updateWatchRoots(List.of(root));

        Map<?, ?> search = MAPPER.readValue(
                tools.searchFiles("private", null, null, null, null, 20), Map.class);
        assertTrue(((List<?>) search.get("results")).isEmpty());
        Map<?, ?> recent = MAPPER.readValue(
                tools.listRecentFiles(null, null, null, 20), Map.class);
        assertEquals(1, ((List<?>) recent.get("results")).size());
        assertTrue(MAPPER.readValue(tools.getFileMetadata(removedPath), Map.class)
                .containsKey("error"));
        Map<?, ?> status = MAPPER.readValue(tools.fileCollectionStatus(), Map.class);
        assertFalse(status.containsKey(removedRoot.toString()));
    }

    @Test
    void disablingCollectionHidesAllHistoricalMetadata() throws Exception {
        tools.updateWatchRoots(List.of());
        Map<?, ?> recent = MAPPER.readValue(
                tools.listRecentFiles(null, null, null, 20), Map.class);
        assertTrue(((List<?>) recent.get("results")).isEmpty());
        assertTrue(MAPPER.readValue(tools.getFileMetadata(trackedPath), Map.class)
                .containsKey("error"));
    }
}
