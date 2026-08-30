package com.selfanalyst.desktop.controller;

import com.selfanalyst.file.FileWatchStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopFileControllerTest {

    @Test
    @SuppressWarnings("unchecked")
    void runningOverviewIncludesRootsCountsAndRecentFiles(@TempDir Path dir) throws Exception {
        Path root = Files.createDirectories(dir.resolve("docs")).toAbsolutePath().normalize();
        FileWatchStore store = new FileWatchStore(dir.resolve("data/file-watch.db"));
        try {
            String path = root.resolve("design.md").toString();
            store.upsertPending(path, "design.md", root.toString(), "md");
            store.updateIndexed(path, 42, Instant.parse("2026-08-30T01:00:00Z"),
                    "hash", "文件采集界面设计", List.of("文件采集", "界面"),
                    "test-model", "file-v1");
            String retiredRoot = dir.resolve("retired").toAbsolutePath().normalize().toString();
            String retiredPath = Path.of(retiredRoot).resolve("old.md").toString();
            store.upsertPending(retiredPath, "old.md", retiredRoot, "md");
            store.updateIndexed(retiredPath, 12, Instant.parse("2026-08-30T02:00:00Z"),
                    "old-hash", "旧目录记录", List.of(), "test-model", "file-v1");

            DesktopFileController controller = new DesktopFileController(
                    true, true, List.of(root), store,
                    () -> true, () -> true, () -> true, null, null);

            Map<String, Object> payload = controller.overviewPayload(20);
            Map<String, Long> totals = (Map<String, Long>) payload.get("totals");
            List<Map<String, Object>> roots =
                    (List<Map<String, Object>>) payload.get("roots");
            List<Map<String, Object>> files =
                    (List<Map<String, Object>>) payload.get("files");

            assertEquals("running", payload.get("status"));
            assertNull(payload.get("reason"));
            assertEquals(1L, totals.get("indexed"));
            assertEquals(root.toString(), roots.get(0).get("path"));
            assertEquals(path, files.get(0).get("path"));
            assertEquals(1, files.size(), "removed watch roots must not leak into the current view");
            assertEquals("文件采集界面设计", files.get(0).get("summary"));
            assertEquals(path, payload.get("latestPath"));
            assertTrue((Boolean) ((Map<String, Object>) payload.get("semantic")).get("available"));
        } finally {
            store.close();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void disabledOverviewRemainsDiscoverableWithoutAStore() {
        DesktopFileController controller = new DesktopFileController(
                false, true, List.of(), null,
                () -> false, () -> false, () -> false, null, null);

        Map<String, Object> payload = controller.overviewPayload(20);

        assertEquals("disabled", payload.get("status"));
        assertEquals("disabled_by_config", payload.get("reason"));
        assertEquals(List.of(), payload.get("roots"));
        assertEquals(List.of(), payload.get("files"));
        assertTrue((Boolean) payload.get("restartRequiredOnChange"));
        assertFalse((Boolean) ((Map<String, Object>) payload.get("semantic")).get("available"));
    }

    @Test
    void degradedOverviewSanitizesStartupError(@TempDir Path dir) {
        DesktopFileController controller = new DesktopFileController(
                true, false, List.of(dir), null,
                () -> false, () -> false, () -> false,
                "worker_start_failed", "first line\r\nsecond line");

        Map<String, Object> payload = controller.overviewPayload(20);

        assertEquals("degraded", payload.get("status"));
        assertEquals("worker_start_failed", payload.get("reason"));
        assertEquals("first line second line", payload.get("error"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void pipelineHealthIsSharedByCoarseAndDetailedStatus(@TempDir Path dir) throws Exception {
        Path root = Files.createDirectories(dir.resolve("docs")).toAbsolutePath().normalize();
        FileWatchStore store = new FileWatchStore(dir.resolve("file-watch.db"));
        DesktopFileController controller = new DesktopFileController(
                true, true, List.of(root), store,
                () -> true, () -> true, () -> false, null, null);
        try {
            assertEquals("running", controller.collectorStatus());
            Map<String, Object> running = controller.overviewPayload(20);
            assertEquals("running", running.get("status"));
            assertFalse((Boolean) ((Map<String, Object>) running.get("semantic")).get("available"),
                    "an unstarted semantic worker must not be reported available");

            store.close();
            assertEquals("degraded", controller.collectorStatus());
            Map<String, Object> degraded = controller.overviewPayload(20);
            assertEquals("degraded", degraded.get("status"));
            assertEquals("store_unavailable", degraded.get("reason"));
        } finally {
            store.close();
        }
    }

    @Test
    void stoppedIndexWorkerDegradesPipeline(@TempDir Path dir) throws Exception {
        Path root = Files.createDirectories(dir.resolve("docs")).toAbsolutePath().normalize();
        try (FileWatchStore store = new FileWatchStore(dir.resolve("file-watch.db"))) {
            DesktopFileController controller = new DesktopFileController(
                    true, false, List.of(root), store,
                    () -> true, () -> false, () -> false, null, null);

            assertEquals("degraded", controller.collectorStatus());
            assertEquals("index_worker_unavailable",
                    controller.overviewPayload(20).get("reason"));
        }
    }
}
