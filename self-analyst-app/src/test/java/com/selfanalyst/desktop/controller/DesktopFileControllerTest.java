package com.selfanalyst.desktop.controller;

import com.selfanalyst.file.FileWatchStore;
import com.selfanalyst.desktop.store.UserConfigStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
            store.updateCollected(path, 42, Instant.parse("2026-08-29T01:00:00Z"),
                    Instant.parse("2026-08-30T01:00:00Z"));
            String retiredRoot = dir.resolve("retired").toAbsolutePath().normalize().toString();
            String retiredPath = Path.of(retiredRoot).resolve("old.md").toString();
            store.upsertPending(retiredPath, "old.md", retiredRoot, "md");
            store.updateCollected(retiredPath, 12, Instant.parse("2026-08-29T02:00:00Z"),
                    Instant.parse("2026-08-30T02:00:00Z"));

            DesktopFileController controller = new DesktopFileController(
                    true, List.of(root), store,
                    () -> true, () -> true, null, null);

            Map<String, Object> payload = controller.overviewPayload(20);
            Map<String, Long> totals = (Map<String, Long>) payload.get("totals");
            List<Map<String, Object>> roots =
                    (List<Map<String, Object>>) payload.get("roots");
            List<Map<String, Object>> files =
                    (List<Map<String, Object>>) payload.get("files");

            assertEquals("running", payload.get("status"));
            assertNull(payload.get("reason"));
            assertEquals(1L, totals.get("collected"));
            assertEquals(root.toString(), roots.get(0).get("path"));
            assertEquals(path, files.get(0).get("path"));
            assertEquals(1, files.size(), "removed watch roots must not leak into the current view");
            assertEquals("design.md", files.get(0).get("name"));
            assertEquals("2026-08-29T01:00:00Z", files.get(0).get("fileCreatedAt"));
            assertFalse(files.get(0).containsKey("summary"));
            assertEquals(path, payload.get("latestPath"));
            assertFalse(payload.containsKey("semantic"));
        } finally {
            store.close();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void disabledOverviewRemainsDiscoverableWithoutAStore() {
        DesktopFileController controller = new DesktopFileController(
                false, List.of(), null,
                () -> false, () -> false, null, null);

        Map<String, Object> payload = controller.overviewPayload(20);

        assertEquals("disabled", payload.get("status"));
        assertEquals("disabled_by_config", payload.get("reason"));
        assertEquals(List.of(), payload.get("roots"));
        assertEquals(List.of(), payload.get("files"));
        assertFalse((Boolean) payload.get("restartRequiredOnChange"));
        assertFalse(payload.containsKey("semantic"));
    }

    @Test
    void settingsPersistMultipleRootsAndApplyWithoutRestart(@TempDir Path dir) throws Exception {
        Path first = Files.createDirectories(dir.resolve("docs-a")).toAbsolutePath().normalize();
        Path second = Files.createDirectories(dir.resolve("docs-b")).toAbsolutePath().normalize();
        UserConfigStore configStore = new UserConfigStore(dir.resolve("memory"));
        AtomicReference<DesktopFileController.CollectorState> state = new AtomicReference<>(
                new DesktopFileController.CollectorState(false, List.of(), false, false,
                        null, null));
        try (FileWatchStore store = new FileWatchStore(dir.resolve("file-watch.db"))) {
            DesktopFileController controller = new DesktopFileController(
                    store, configStore, state::get,
                    (enabled, roots) -> state.set(new DesktopFileController.CollectorState(
                            enabled, roots, enabled, enabled, null, null)));

            Map<String, Object> payload = controller.updateSettings(true,
                    List.of(first.toString(), second.toString(), first.toString()));

            assertEquals("true", configStore.loadUser().getProperty("file.watch.enabled"));
            assertEquals(first + "," + second,
                    configStore.loadUser().getProperty("file.watch.paths"));
            assertEquals("running", payload.get("status"));
            assertFalse((Boolean) payload.get("restartRequiredOnChange"));
            assertEquals(List.of(first, second), state.get().watchRoots());
        }
    }

    @Test
    void enablingWithoutWatchRootsPersistsAndLeavesCollectorDegraded(@TempDir Path dir)
            throws Exception {
        UserConfigStore configStore = new UserConfigStore(dir.resolve("memory"));
        AtomicReference<DesktopFileController.CollectorState> state = new AtomicReference<>(
                new DesktopFileController.CollectorState(false, List.of(), false, false,
                        null, null));
        DesktopFileController controller = new DesktopFileController(
                null, configStore, state::get,
                (enabled, roots) -> state.set(new DesktopFileController.CollectorState(
                        enabled, roots, enabled, enabled, null, null)));

        Map<String, Object> payload = controller.updateSettings(true, List.of());

        assertEquals("true", configStore.loadUser().getProperty("file.watch.enabled"));
        assertNull(configStore.loadUser().getProperty("file.watch.paths"));
        assertEquals("degraded", payload.get("status"));
        assertEquals("paths_unavailable", payload.get("reason"));
        assertTrue((Boolean) payload.get("enabled"));
        assertEquals(List.of(), state.get().watchRoots());
    }

    @Test
    void invalidDirectoryIsRejectedBeforePersistenceOrRuntimeApply(@TempDir Path dir) throws Exception {
        UserConfigStore configStore = new UserConfigStore(dir.resolve("memory"));
        AtomicBoolean applied = new AtomicBoolean();
        DesktopFileController controller = new DesktopFileController(
                null, configStore,
                () -> new DesktopFileController.CollectorState(false, List.of(), false, false,
                        null, null),
                (enabled, roots) -> applied.set(true));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> controller.updateSettings(true, List.of(dir.resolve("missing").toString())));

        assertTrue(error.getMessage().contains("不存在或不是目录"));
        assertFalse(applied.get());
        assertNull(configStore.loadUser().getProperty("file.watch.paths"));
    }

    @Test
    void concurrentSettingsSavesKeepPersistenceAndRuntimeInTheSameOrder(@TempDir Path dir)
            throws Exception {
        Path first = Files.createDirectories(dir.resolve("first")).toAbsolutePath().normalize();
        Path second = Files.createDirectories(dir.resolve("second")).toAbsolutePath().normalize();
        UserConfigStore configStore = new UserConfigStore(dir.resolve("memory"));
        AtomicReference<List<Path>> appliedRoots = new AtomicReference<>(List.of());
        CountDownLatch firstApplyEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstApply = new CountDownLatch(1);
        AtomicBoolean firstCall = new AtomicBoolean(true);
        DesktopFileController controller = new DesktopFileController(
                null, configStore,
                () -> new DesktopFileController.CollectorState(true, appliedRoots.get(), true, true,
                        null, null),
                (enabled, roots) -> {
                    if (firstCall.compareAndSet(true, false)) {
                        firstApplyEntered.countDown();
                        try {
                            releaseFirstApply.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(interrupted);
                        }
                    }
                    appliedRoots.set(roots);
                });

        try (var executor = Executors.newFixedThreadPool(2)) {
            var firstSave = executor.submit(() -> controller.updateSettings(
                    true, List.of(first.toString())));
            assertTrue(firstApplyEntered.await(5, TimeUnit.SECONDS));
            var secondSave = executor.submit(() -> controller.updateSettings(
                    true, List.of(second.toString())));
            releaseFirstApply.countDown();
            firstSave.get(5, TimeUnit.SECONDS);
            secondSave.get(5, TimeUnit.SECONDS);
        }

        assertEquals(second.toString(),
                configStore.loadUser().getProperty("file.watch.paths"));
        assertEquals(List.of(second), appliedRoots.get());
    }

    @Test
    void degradedOverviewSanitizesStartupError(@TempDir Path dir) {
        DesktopFileController controller = new DesktopFileController(
                true, List.of(dir), null,
                () -> false, () -> false,
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
                true, List.of(root), store,
                () -> true, () -> true, null, null);
        try {
            assertEquals("running", controller.collectorStatus());
            Map<String, Object> running = controller.overviewPayload(20);
            assertEquals("running", running.get("status"));
            assertFalse(running.containsKey("semantic"));

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
    void stoppedMetadataWorkerDegradesPipeline(@TempDir Path dir) throws Exception {
        Path root = Files.createDirectories(dir.resolve("docs")).toAbsolutePath().normalize();
        try (FileWatchStore store = new FileWatchStore(dir.resolve("file-watch.db"))) {
            DesktopFileController controller = new DesktopFileController(
                    true, List.of(root), store,
                    () -> true, () -> false, null, null);

            assertEquals("degraded", controller.collectorStatus());
            assertEquals("metadata_worker_unavailable",
                    controller.overviewPayload(20).get("reason"));
        }
    }
}
