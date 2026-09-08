package com.selfanalyst.events.raw;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.sql.DriverManager;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class RawStartupIntegrityTest {
    private static final YearMonth AUGUST = YearMonth.of(2026, 8);
    private static final YearMonth SEPTEMBER = YearMonth.of(2026, 9);

    private static void seed(Path dir) throws Exception {
        try (var raw = new RawEventStore(dir)) {
            for (String month : List.of("2026-08", "2026-09")) {
                Instant at = Instant.parse(month + "-03T12:00:00Z");
                raw.append(RawEvent.create(new RawEventIdGenerator(), month, "test-bucket",
                        RawEventSource.WINDOW, 1, RawIngestKind.HEARTBEAT, at, at, 5,
                        Map.of("app", "synthetic-editor"), null, null));
            }
        }
    }

    private static Path partition(Path dir, YearMonth month) {
        return dir.resolve("2026/raw-events-" + month + ".db");
    }

    @Test
    void latestChecksOnlyNewestAndAllChecksOlderSealedManifest(@TempDir Path dir) throws Exception {
        seed(dir);
        Path older = partition(dir, AUGUST);
        Path manifest = RawEventStore.manifestPath(older);
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode json = (ObjectNode) mapper.readTree(Files.readString(manifest));
        json.put("fileSha256", "invalid-private-sentinel");
        Files.writeString(manifest, mapper.writeValueAsString(json));
        String manifestBefore = Files.readString(manifest);
        String rawBefore = RawEventStore.fileSha256(older);
        Instant oldVerified;
        try (var catalog = new RawPartitionCatalog(dir)) {
            oldVerified = catalog.find(AUGUST.toString()).orElseThrow().verifiedAt();
        }
        try (var raw = new RawEventStore(dir)) {
            raw.verifyOnStartup(false);
            try (var catalog = new RawPartitionCatalog(dir)) {
                assertEquals(oldVerified, catalog.find(AUGUST.toString()).orElseThrow().verifiedAt());
                assertNotNull(catalog.find(SEPTEMBER.toString()).orElseThrow().verifiedAt());
            }
            var error = assertThrows(RawStartupIntegrityException.class, () -> raw.verifyOnStartup(true));
            assertTrue(error.getMessage().contains("2026-08"));
            assertFalse(error.getMessage().contains(dir.toString()));
            assertFalse(error.getMessage().contains("invalid-private-sentinel"));
            assertNull(error.getCause());
        }
        assertEquals(manifestBefore, Files.readString(manifest));
        assertEquals(rawBefore, RawEventStore.fileSha256(older));
        try (var catalog = new RawPartitionCatalog(dir)) {
            assertEquals(RawPartitionStatus.QUARANTINED, catalog.find(AUGUST.toString()).orElseThrow().status());
            assertEquals(RawPartitionStatus.ACTIVE, catalog.find(SEPTEMBER.toString()).orElseThrow().status());
        }
    }

    @Test
    void repeatedAllChecksKeepRawAndManifestBytes(@TempDir Path dir) throws Exception {
        seed(dir);
        Path older = partition(dir, AUGUST);
        Path newer = partition(dir, SEPTEMBER);
        String olderHash = RawEventStore.fileSha256(older);
        String newerHash = RawEventStore.fileSha256(newer);
        String manifest = Files.readString(RawEventStore.manifestPath(older));
        try (var raw = new RawEventStore(dir)) {
            raw.verifyOnStartup(true);
            raw.verifyOnStartup(true);
            assertEquals(1, raw.count(AUGUST));
            assertEquals(1, raw.count(SEPTEMBER));
        }
        assertEquals(olderHash, RawEventStore.fileSha256(older));
        assertEquals(newerHash, RawEventStore.fileSha256(newer));
        assertEquals(manifest, Files.readString(RawEventStore.manifestPath(older)));
    }

    @Test
    void latestRejectsCatalogStatisticsMismatchWithoutRepair(@TempDir Path dir) throws Exception {
        seed(dir);
        Path newer = partition(dir, SEPTEMBER);
        String before = RawEventStore.fileSha256(newer);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("catalog.db"));
             var statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE raw_partitions SET event_count=99 WHERE partition_month='2026-09'");
        }
        try (var raw = new RawEventStore(dir)) {
            assertThrows(RawStartupIntegrityException.class, () -> raw.verifyOnStartup(false));
        }
        try (var catalog = new RawPartitionCatalog(dir)) {
            var row = catalog.find(SEPTEMBER.toString()).orElseThrow();
            assertEquals(99, row.eventCount());
            assertEquals(RawPartitionStatus.QUARANTINED, row.status());
        }
        assertEquals(before, RawEventStore.fileSha256(newer));
    }

    @Test
    void missingSelectedPartitionDoesNotCreateReplacement(@TempDir Path dir) throws Exception {
        seed(dir);
        Path newer = partition(dir, SEPTEMBER);
        Path preserved = newer.resolveSibling("preserved.db");
        Files.move(newer, preserved);
        try (var raw = new RawEventStore(dir)) {
            assertThrows(RawStartupIntegrityException.class, () -> raw.verifyOnStartup(false));
        }
        assertFalse(Files.exists(newer));
        assertTrue(Files.exists(preserved));
    }

    @Test
    void missingManifestAndCorruptDatabaseAreRejected(@TempDir Path root) throws Exception {
        Path missing = root.resolve("missing");
        seed(missing);
        Path manifest = RawEventStore.manifestPath(partition(missing, AUGUST));
        Path preserved = manifest.resolveSibling("preserved.json");
        Files.move(manifest, preserved);
        try (var raw = new RawEventStore(missing)) {
            assertThrows(RawStartupIntegrityException.class, () -> raw.verifyOnStartup(true));
        }
        assertFalse(Files.exists(manifest));

        Path corrupt = root.resolve("corrupt");
        seed(corrupt);
        Path database = partition(corrupt, SEPTEMBER);
        Files.writeString(database, "synthetic-invalid-sqlite");
        try (var raw = new RawEventStore(corrupt)) {
            assertThrows(RawStartupIntegrityException.class, () -> raw.verifyOnStartup(false));
        }
        assertEquals("synthetic-invalid-sqlite", Files.readString(database));
    }

    @Test
    void emptyCatalogAcceptsEitherScope(@TempDir Path dir) throws Exception {
        try (var raw = new RawEventStore(dir); var catalog = new RawPartitionCatalog(dir)) {
            raw.verifyOnStartup(false);
            raw.verifyOnStartup(true);
            assertEquals(0, catalog.partitionCount());
        }
    }
}
