package com.selfanalyst;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeStorageGuardTest {
    @TempDir Path root;

    @Test void publishesMergedFormatAndAcceptsItOnRestart() throws Exception {
        try (var guard = RuntimeStorageGuard.acquire(root, path -> {})) {
            guard.publishMergedFormat();
            assertEquals("{\"formatVersion\":2}\n", Files.readString(root.resolve("storage-format.json")));
        }
        try (var guard = RuntimeStorageGuard.acquire(root, path -> fail("marked root needs no adoption"))) {
            assertEquals(root.toRealPath(), guard.dataRoot());
        }
    }

    @Test void formatTwoIsPublishedBeforeAnInterruptedDatabaseSwitch() throws Exception {
        // macOS exposes its temporary directory through /var -> /private/var.
        // Use the same real-path identity as data-root admission; do not ask the
        // migration's link guard to follow the platform alias.
        Path events = root.toRealPath().resolve("events");
        try (var old = new com.selfanalyst.events.store.Database(events)) { }
        var published = new java.util.concurrent.atomic.AtomicBoolean();
        try (var guard = RuntimeStorageGuard.acquire(root, path -> {})) {
            assertThrows(IllegalStateException.class, () -> new com.selfanalyst.events.store.MergedStorageMigration(
                    events, events.resolve("raw"), () -> {
                        try {
                            assertTrue(Files.readString(events.resolve("merged-migration.json")).contains("prepared"));
                            guard.publishMergedFormat();
                            published.set(true);
                        } catch (IOException e) { throw new java.io.UncheckedIOException(e); }
                        throw new IllegalStateException("power loss before switching");
                    }));
            assertTrue(published.get(), "The failure must occur after reaching the format-publication hook");
            assertTrue(Files.readString(root.resolve("storage-format.json")).contains(":2"));
            try (var c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + events.resolve("events.db").toUri() + "?mode=ro")) {
                assertFalse(com.selfanalyst.events.store.Database.hasMergedSchema(c));
            }
        }
        try (var guard = RuntimeStorageGuard.acquire(root, path -> fail("version 2 admitted"));
             var resumed = new com.selfanalyst.events.store.MergedStorageMigration(events, events.resolve("raw"));
             var db = new com.selfanalyst.events.store.Database(events)) {
            assertTrue(com.selfanalyst.events.store.Database.hasMergedSchema(db.metaConnection()));
        }
    }

    @Test void holdsLockAcrossAliasesAndLeavesReusableLockFile() throws Exception {
        var guard = RuntimeStorageGuard.acquire(root, path -> {});
        var failure = assertThrows(RuntimeStorageGuard.StorageException.class,
                () -> RuntimeStorageGuard.acquire(root.resolve("."), path -> fail("must not inspect")));
        assertEquals(RuntimeStorageGuard.Failure.DATA_IN_USE, failure.failure());
        guard.close();
        guard.close();
        assertTrue(Files.exists(root.resolve("app.lock")));
        try (var next = RuntimeStorageGuard.acquire(root, path -> fail("already marked"))) {
            assertEquals(root.toRealPath(), next.dataRoot());
        }
    }

    @Test void validatesBeforePublishingAndPreservesBusinessBytes() throws Exception {
        Path business = root.resolve("existing.dat");
        byte[] content = {1, 2, 3};
        Files.write(business, content);
        AtomicInteger calls = new AtomicInteger();
        try (var guard = RuntimeStorageGuard.acquire(root, path -> {
            calls.incrementAndGet();
            assertFalse(Files.exists(path.resolve("storage-format.json")));
        })) {
            assertEquals(1, calls.get());
            assertArrayEquals(content, Files.readAllBytes(business));
            assertEquals("{\"formatVersion\":1}\n", Files.readString(root.resolve("storage-format.json")));
        }
    }

    @Test void failedCompatibilityDoesNotPublishAndReleasesLock() throws Exception {
        assertThrows(IOException.class, () -> RuntimeStorageGuard.acquire(root, path -> {
            throw new IOException("private diagnostic must not escape");
        }));
        assertFalse(Files.exists(root.resolve("storage-format.json")));
        try (var ignored = RuntimeStorageGuard.acquire(root, path -> {})) { }
    }

    @Test void rejectsInvalidAndUnsupportedMarkersWithoutOverwriting() throws Exception {
        for (String content : new String[]{"", "{}", "null", "{\"formatVersion\":\"1\"}",
                "{\"formatVersion\":1,\"formatVersion\":1}", "{\"formatVersion\":1} {}",
                "{\"formatVersion\":0}", "{\"formatVersion\":3}"}) {
            Path marker = root.resolve("storage-format.json");
            Files.writeString(marker, content);
            assertThrows(RuntimeStorageGuard.StorageException.class,
                    () -> RuntimeStorageGuard.acquire(root, path -> fail("must not adopt invalid marker")));
            assertEquals(content, Files.readString(marker));
        }
    }

    @Test void staleTemporaryMarkerRequiresFreshCheck() throws Exception {
        Files.writeString(root.resolve("storage-format.json.tmp"), "untrusted");
        AtomicInteger checks = new AtomicInteger();
        try (var ignored = RuntimeStorageGuard.acquire(root, path -> checks.incrementAndGet())) { }
        assertEquals(1, checks.get());
        assertFalse(Files.exists(root.resolve("storage-format.json.tmp")));
    }

    @Test void lockPathDirectoryFailsClosed() throws Exception {
        Files.createDirectory(root.resolve("app.lock"));
        var failure = assertThrows(RuntimeStorageGuard.StorageException.class,
                () -> RuntimeStorageGuard.acquire(root, path -> fail("must not inspect")));
        assertEquals(RuntimeStorageGuard.Failure.DATA_LOCK_UNAVAILABLE, failure.failure());
    }

    @Test void nonDirectoryRootHasDistinctFailureCode() throws Exception {
        Path file = root.resolve("file");
        Files.writeString(file, "keep");
        var failure = assertThrows(RuntimeStorageGuard.StorageException.class,
                () -> RuntimeStorageGuard.acquire(file, path -> fail("must not inspect")));
        assertEquals(RuntimeStorageGuard.Failure.DATA_ROOT_UNAVAILABLE, failure.failure());
        assertEquals("keep", Files.readString(file));
    }

    @Test void markerPublishFailureDoesNotModifyBusinessFile() throws Exception {
        Files.createDirectory(root.resolve("storage-format.json.tmp"));
        Files.writeString(root.resolve("business"), "unchanged");
        assertThrows(IOException.class, () -> RuntimeStorageGuard.acquire(root, path -> {}));
        assertFalse(Files.exists(root.resolve("storage-format.json")));
        assertEquals("unchanged", Files.readString(root.resolve("business")));
    }
}
