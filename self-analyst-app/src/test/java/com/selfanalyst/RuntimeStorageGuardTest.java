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
                "{\"formatVersion\":0}", "{\"formatVersion\":2}"}) {
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
