package com.selfanalyst.events.raw;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RawPartitionCatalogTest {

    @Test
    void firstOpenCreatesIdempotentSchemaAndRepeatedOpenPreservesRows(@TempDir Path dir)
            throws Exception {
        Path raw = dir.resolve("raw");
        try (RawPartitionCatalog catalog = new RawPartitionCatalog(raw)) {
            assertEquals(raw.toRealPath(), catalog.rawRoot());
            assertTrue(Files.isRegularFile(catalog.catalogPath()));
            assertFalse(catalog.hasPartitions());
            catalog.insert(activePartition("2026-09", "2026/raw-events-2026-09.db"));
            assertEquals(1, catalog.partitionCount());
        }

        try (RawPartitionCatalog reopened = new RawPartitionCatalog(raw)) {
            assertTrue(reopened.hasPartitions());
            assertEquals(1, reopened.partitionCount());
        }

        try (var connection = DriverManager.getConnection(
                "jdbc:sqlite:" + raw.resolve("catalog.db").toAbsolutePath());
             var statement = connection.createStatement();
             var columns = statement.executeQuery("PRAGMA table_info(raw_partitions)")) {
            Set<String> names = new HashSet<>();
            while (columns.next()) names.add(columns.getString("name"));
            assertTrue(names.containsAll(Set.of(
                    "partition_month", "relative_path", "received_start", "received_end",
                    "status", "event_count", "first_event_id", "last_event_id",
                    "size_bytes", "file_sha256", "verified_at", "schema_version")),
                    names.toString());
        }
    }

    @Test
    void partitionPathsCannotEscapeRawRoot(@TempDir Path dir) throws Exception {
        try (RawPartitionCatalog catalog = new RawPartitionCatalog(dir.resolve("raw"))) {
            assertThrows(IllegalArgumentException.class,
                    () -> catalog.resolvePartitionPath("../outside.db"));
            assertThrows(IllegalArgumentException.class,
                    () -> catalog.resolvePartitionPath(dir.resolve("outside.db").toString()));
            assertTrue(catalog.resolvePartitionPath("2026/raw-events-2026-09.db")
                    .startsWith(catalog.rawRoot()));
        }
    }

    @Test
    void linkedRawDirectoryAndLinkedPartitionPathFailClosed(@TempDir Path dir)
            throws Exception {
        Path target = Files.createDirectory(dir.resolve("target"));
        Path linkedRoot = dir.resolve("linked-raw");
        createLinkOrSkip(linkedRoot, target);

        assertThrows(IllegalStateException.class, () -> new RawPartitionCatalog(linkedRoot));
        deleteLink(linkedRoot);

        Path raw = dir.resolve("safe-raw");
        try (RawPartitionCatalog catalog = new RawPartitionCatalog(raw)) {
            Path outside = Files.createDirectory(dir.resolve("outside"));
            Path linkedYear = raw.resolve("2026");
            createLinkOrSkip(linkedYear, outside);
            try {
                assertThrows(IllegalArgumentException.class,
                        () -> catalog.resolvePartitionPath("2026/raw-events-2026-09.db"));
            } finally {
                deleteLink(linkedYear);
            }
        }
    }

    private static RawPartitionMetadata activePartition(String month, String relativePath) {
        return new RawPartitionMetadata(month, relativePath,
                Instant.parse(month + "-01T00:00:00Z"), null,
                RawPartitionStatus.ACTIVE, 0, null, null, 0, null, null,
                RawPartitionCatalog.CATALOG_SCHEMA_VERSION);
    }

    private static void createLinkOrSkip(Path link, Path target) throws Exception {
        try {
            Files.createSymbolicLink(link, target);
            return;
        } catch (UnsupportedOperationException | IOException | SecurityException unavailable) {
            if (System.getProperty("os.name", "").toLowerCase().contains("windows")) {
                Process junction = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J",
                        link.toString(), target.toString()).redirectErrorStream(true).start();
                String output = new String(junction.getInputStream().readAllBytes(),
                        java.nio.charset.Charset.defaultCharset());
                if (junction.waitFor() == 0) return;
                Assumptions.abort("当前文件系统无法创建链接: " + output);
            }
            Assumptions.abort("当前文件系统无法创建符号链接: " + unavailable.getMessage());
        }
    }

    private static void deleteLink(Path link) throws Exception {
        try {
            Files.deleteIfExists(link);
            return;
        } catch (IOException deleteFailure) {
            if (System.getProperty("os.name", "").toLowerCase().contains("windows")) {
                Process removeJunction = new ProcessBuilder("cmd.exe", "/c", "rmdir",
                        link.toString()).redirectErrorStream(true).start();
                String output = new String(removeJunction.getInputStream().readAllBytes(),
                        java.nio.charset.Charset.defaultCharset());
                if (removeJunction.waitFor() == 0) return;
                deleteFailure.addSuppressed(new IOException(output));
            }
            throw deleteFailure;
        }
    }
}
