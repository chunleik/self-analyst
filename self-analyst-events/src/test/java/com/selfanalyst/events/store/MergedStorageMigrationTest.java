package com.selfanalyst.events.store;

import com.selfanalyst.events.model.*;
import com.selfanalyst.events.projection.*;
import com.selfanalyst.events.raw.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MergedStorageMigrationTest {
    @TempDir Path root;
    @Test void freshStorageIsCompactAndExclusivelyLocked() throws Exception {
        try (var migration = new MergedStorageMigration(root, root.resolve("raw")); var db = new Database(root)) {
            assertTrue(Database.hasMergedSchema(db.metaConnection()));
            assertFalse(Files.exists(root.resolve("raw")));
            assertThrows(IllegalStateException.class, () -> new ProjectionRebuildService(root, root.resolve("raw"), 120, 1000, "v1").rebuild());
            try (var s = db.metaConnection().createStatement(); var r = s.executeQuery("SELECT count(*) FROM sqlite_master WHERE name LIKE 'raw_%'")) {
                assertTrue(r.next()); assertEquals(0, r.getInt(1));
            }
            assertThrows(IllegalStateException.class, () -> new MergedStorageMigration(root, root.resolve("raw")));
        }
        try (var restarted = new MergedStorageMigration(root, root.resolve("raw"))) {
            assertEquals("complete", restarted.backupStatus().get("migration"));
        }
    }

    @Test void migratesHistoryWithoutChangingSourceRawAndCleansOnlyRegisteredBackups() throws Exception {
        Instant time = Instant.parse("2026-09-16T00:00:00Z"); long eventId;
        try (var db = new Database(root); var raw = new RawEventStore(root.resolve("raw"))) {
            var bucket = Bucket.create("test", "test", "test", "test", "host");
            new BucketStore(db).create(bucket);
            var service = new HeartbeatIngestionService(raw, new EventProjector(db, 120, 1000, "v1"));
            eventId = service.ingest(bucket, new Event(time, 2, Map.of("app", "A")), "a").projectionEventId();
            service.ingest(bucket, new Event(time.plusSeconds(1), 2, Map.of("app", "A")), "b");
        }
        var partition = root.resolve("raw/2026/raw-events-2026-09.db");
        String hash = RawEventStore.fileSha256(partition);
        Path unrelated = root.resolve("keep.txt"); Files.writeString(unrelated, "keep");
        try (var migration = new MergedStorageMigration(root, root.resolve("raw")); var db = new Database(root)) {
            var events = new EventStore(db, PulseTimeConfig.DEFAULT).queryAllEvents("test");
            assertEquals(1, events.size()); assertEquals(eventId, events.getFirst().id());
            assertEquals(3, events.getFirst().duration());
            try (var writer = new MergedEventStore(db, 120)) {
                assertEquals(eventId, writer.heartbeat("test", new Event(time, 2, Map.of("app", "A")), "a", null).id());
                assertEquals(1, new EventStore(db, PulseTimeConfig.DEFAULT).countByBucket("test"));
            }
            assertEquals(hash, RawEventStore.fileSha256(partition));
            assertThrows(IllegalArgumentException.class, () -> migration.cleanBackups("wrong"));
            String id = (String) migration.backupStatus().get("migrationId");
            migration.cleanBackups(id);
            assertFalse(Files.exists(partition)); assertTrue(Files.exists(unrelated));
            assertEquals(1, events.size());
        }
    }

    @Test void refusesUnknownRawFilesAndPreservesSource() throws Exception {
        Files.createDirectories(root.resolve("raw")); Files.writeString(root.resolve("raw/private.txt"), "keep");
        assertThrows(IllegalStateException.class, () -> new MergedStorageMigration(root, root.resolve("raw")));
        assertEquals("keep", Files.readString(root.resolve("raw/private.txt")));
    }

    @Test void recoversEveryDurableSwitchBoundary() throws Exception {
        for (String phase : List.of("copied", "validated", "prepared", "switched")) {
            Path dir = root.resolve(phase); Files.createDirectory(dir);
            try (var db = new Database(dir)) {
                new BucketStore(db).create(Bucket.create("test", "test", "test", "test", "test"));
                new EventStore(db, PulseTimeConfig.DEFAULT).insertEvent("test", new Event(Instant.EPOCH, 5, Map.of("app", "A")));
            }
            assertThrows(IllegalStateException.class, () -> new MergedStorageMigration(dir, dir.resolve("raw"),
                    at -> { if (at.equals(phase)) throw new IllegalStateException("interrupted"); }));
            try (var migration = new MergedStorageMigration(dir, dir.resolve("raw")); var db = new Database(dir)) {
                var events = new EventStore(db, PulseTimeConfig.DEFAULT).queryAllEvents("test");
                assertEquals(1, events.size()); assertEquals(5, events.getFirst().duration());
                assertTrue(Database.hasMergedSchema(db.metaConnection()));
            }
        }
    }

    @Test void noSpaceUnknownSchemaAndCorruptionNeverCreateAnEmptyReplacement() throws Exception {
        assertThrows(IllegalStateException.class, () -> new MergedStorageMigration(root, root.resolve("raw"), step -> {}, path -> 0));
        assertFalse(Files.exists(root.resolve("events.db")));
        try (var c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + root.resolve("events.db")); var s = c.createStatement()) {
            s.execute("CREATE TABLE unknown(value TEXT)");
        }
        String hash = RawEventStore.fileSha256(root.resolve("events.db"));
        assertThrows(IllegalStateException.class, () -> new MergedStorageMigration(root, root.resolve("raw")));
        assertEquals(hash, RawEventStore.fileSha256(root.resolve("events.db")));
        Files.writeString(root.resolve("events.db"), "invalid database");
        assertThrows(IllegalStateException.class, () -> new MergedStorageMigration(root, root.resolve("raw")));
        assertEquals("invalid database", Files.readString(root.resolve("events.db")));
    }

    @Test void pendingTailIsRecoveredButDeletedBucketIsNotResurrected() throws Exception {
        for (boolean deleted : List.of(false, true)) {
            Path dir = root.resolve(deleted ? "deleted" : "pending");
            Path rawDir = root.resolve(deleted ? "external-deleted-raw" : "external-pending-raw");
            Instant time = Instant.parse("2026-09-16T00:00:00Z");
            try (var db = new Database(dir); var raw = new RawEventStore(rawDir)) {
                Bucket bucket = Bucket.create("test", "test", "window", "window", "test");
                new BucketStore(db).create(bucket);
                var projector = new EventProjector(db, 5, 1000, "v1");
                new HeartbeatIngestionService(raw, projector).ingest(bucket, new Event(time, 1, Map.of("app", "A")), "a");
                if (deleted) {
                    new EventStore(db, PulseTimeConfig.DEFAULT).deleteByBucket("test"); new BucketStore(db).delete("test");
                } else raw.append(RawEvent.create(new RawEventIdGenerator(), "pending", "test", RawEventSource.WINDOW, 1,
                        RawIngestKind.HEARTBEAT, time.plusSeconds(30), Instant.now().plusSeconds(1), 1, Map.of("app", "B"), null, null));
            }
            if (deleted) {
                assertThrows(IllegalStateException.class, () -> new MergedStorageMigration(dir, rawDir));
                try (var db = new Database(dir)) { assertTrue(new BucketStore(db).get("test").isEmpty()); }
            } else try (var migration = new MergedStorageMigration(dir, rawDir); var db = new Database(dir)) {
                assertEquals(2, new EventStore(db, PulseTimeConfig.DEFAULT).countByBucket("test"));
            }
        }
    }

    @Test void cleanupRejectsActiveDatabaseAndChangedBackup() throws Exception {
        try (var db = new Database(root)) { }
        try (var migration = new MergedStorageMigration(root, root.resolve("raw"))) {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            Path journal = root.resolve("merged-migration.json");
            var state = mapper.readTree(Files.readString(journal));
            var files = (com.fasterxml.jackson.databind.node.ArrayNode) state.path("files");
            files.addObject().put("path", root.resolve("events.db").toString())
                    .put("sha256", RawEventStore.fileSha256(root.resolve("events.db")));
            Files.writeString(journal, mapper.writeValueAsString(state));
            assertThrows(IllegalStateException.class, () -> migration.cleanBackups(state.path("id").asText()));
            assertTrue(Files.exists(root.resolve("events.db")));
        }
    }

    @Test void missingAuthoritativeTableIsNotSilentlyRecreated() throws Exception {
        try (var migration = new MergedStorageMigration(root, root.resolve("raw")); var db = new Database(root)) {
            try (var s = db.metaConnection().createStatement()) { s.execute("DROP TABLE events"); }
        }
        assertThrows(IllegalStateException.class, () -> new MergedStorageMigration(root, root.resolve("raw")));
    }

    @Test void linkedRawDirectoryIsRejected() throws Exception {
        Path actual = Files.createDirectory(root.resolve("actual-raw"));
        Path link = root.resolve("linked-raw");
        if (System.getProperty("os.name").startsWith("Windows")) {
            var process = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), actual.toString())
                    .redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes());
            assertEquals(0, process.waitFor(), output);
        } else Files.createSymbolicLink(link, actual);
        assertThrows(IllegalStateException.class, () -> new MergedStorageMigration(root.resolve("events"), link));
    }

    @Test void changedBackupIsPreservedAndCleanupCanBeRetried() throws Exception {
        try (var db = new Database(root)) { }
        try (var migration = new MergedStorageMigration(root, root.resolve("raw"))) {
            var state = new com.fasterxml.jackson.databind.ObjectMapper().readTree(Files.readString(root.resolve("merged-migration.json")));
            Path backup = Path.of(state.path("files").get(0).path("path").asText());
            byte[] original = Files.readAllBytes(backup);
            Files.writeString(backup, "changed backup");
            assertThrows(IllegalStateException.class, () -> migration.cleanBackups(state.path("id").asText()));
            assertEquals("changed backup", Files.readString(backup));
            Files.write(backup, original);
            migration.cleanBackups(state.path("id").asText());
            migration.cleanBackups(state.path("id").asText());
            assertTrue(Files.exists(root.resolve("events.db")));
        }
    }
}
