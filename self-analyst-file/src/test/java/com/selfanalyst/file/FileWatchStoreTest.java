package com.selfanalyst.file;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FileWatchStoreTest {

    private FileWatchStore store;
    private Path dbPath;

    @BeforeEach
    void setUp(@TempDir Path tmp) {
        dbPath = tmp.resolve("file-watch.db");
        store = new FileWatchStore(dbPath);
    }

    @AfterEach
    void tearDown() {
        if (store != null) store.close();
    }

    @Test
    void upsertCreatesPending() {
        assertTrue(store.isHealthy());
        store.upsertPending("/a/b.txt", "b.txt", "/a", "txt");
        FileRecord rec = store.findByPath("/a/b.txt");
        assertNotNull(rec);
        assertEquals(FileStatus.PENDING, rec.status());
        assertEquals("txt", rec.extension());
    }

    @Test
    void healthProbeTurnsFalseAfterClose() {
        store.close();
        assertFalse(store.isHealthy());
        store = null;
    }

    @Test
    void statusFlowsPendingToCollectedMetadata() {
        store.upsertPending("/a/b.txt", "b.txt", "/a", "txt");
        Instant created = Instant.parse("2026-06-14T10:00:00Z");
        Instant modified = Instant.parse("2026-06-15T10:00:00Z");
        store.updateCollected("/a/b.txt", 123, created, modified);

        FileRecord rec = store.findByPath("/a/b.txt");
        assertEquals(FileStatus.COLLECTED, rec.status());
        assertEquals(123, rec.sizeBytes());
        assertEquals(created, rec.fileCreatedAt());
        assertEquals(modified, rec.lastModified());
        assertNotNull(rec.lastCollectedAt());
    }

    @Test
    void reupsertKeepsLastMetadataSnapshot() {
        store.upsertPending("/a/b.txt", "b.txt", "/a", "txt");
        Instant created = Instant.parse("2026-06-14T10:00:00Z");
        Instant modified = Instant.parse("2026-06-15T10:00:00Z");
        store.updateCollected("/a/b.txt", 10, created, modified);
        store.upsertPending("/a/b.txt", "b.txt", "/a", "txt");

        FileRecord rec = store.findByPath("/a/b.txt");
        assertEquals(FileStatus.PENDING, rec.status());
        assertEquals(created, rec.fileCreatedAt());
        assertEquals(modified, rec.lastModified());
    }

    @Test
    void markDeletedTerminal() {
        store.upsertPending("/a/b.txt", "b.txt", "/a", "txt");
        store.markDeleted("/a/b.txt");
        assertEquals(FileStatus.DELETED, store.findByPath("/a/b.txt").status());
    }

    @Test
    void findRetryableDrivenByNextRetryAt() {
        store.upsertPending("/a/past.txt", "past.txt", "/a", "txt");
        store.upsertPending("/a/future.txt", "future.txt", "/a", "txt");
        store.markFailed("/a/past.txt", "FILE_METADATA_FAILED:IOException",
                Instant.now().minusSeconds(60));
        store.markFailed("/a/future.txt", "FILE_METADATA_FAILED:IOException",
                Instant.now().plusSeconds(3600));

        List<String> paths = store.findRetryable(10).stream()
                .map(FileRecord::absolutePath).toList();
        assertTrue(paths.contains("/a/past.txt"));
        assertFalse(paths.contains("/a/future.txt"));
    }

    @Test
    void queryByTimeReturnsCollectedOnly() {
        store.upsertPending("/a/old.txt", "old.txt", "/a", "txt");
        store.upsertPending("/a/new.txt", "new.txt", "/a", "txt");
        store.updateCollected("/a/old.txt", 1, Instant.parse("2026-06-01T00:00:00Z"),
                Instant.parse("2026-06-10T00:00:00Z"));
        store.updateCollected("/a/new.txt", 1, Instant.parse("2026-06-01T00:00:00Z"),
                Instant.parse("2026-06-18T00:00:00Z"));

        List<FileRecord> hits = store.queryByTime(
                Instant.parse("2026-06-15T00:00:00Z"),
                Instant.parse("2026-06-20T00:00:00Z"), null, null, 10);
        assertEquals(List.of("/a/new.txt"),
                hits.stream().map(FileRecord::absolutePath).toList());
    }

    @Test
    void localMetadataSearchUsesPathWithoutEmbedding() {
        store.upsertPending("/a/design-plan.md", "docs/design-plan.md", "/a", "md");
        store.updateCollected("/a/design-plan.md", 1, Instant.now(), Instant.now());

        assertEquals(1, store.queryMetadata("design", null, null, null, "md", 10).size());
        assertTrue(store.queryMetadata("database", null, null, null, null, 10).isEmpty());
    }

    @Test
    void statusCountsAndRecentUseCollectedTerminology() throws Exception {
        store.upsertPending("/a/first.txt", "first.txt", "/a", "txt");
        store.updateCollected("/a/first.txt", 1, Instant.now(), Instant.now());
        Thread.sleep(5);
        store.upsertPending("/a/second.txt", "second.txt", "/a", "txt");
        store.updateCollected("/a/second.txt", 1, Instant.now(), Instant.now());

        Map<String, Long> counts = store.statusCountsByWatchRoot().get("/a");
        assertEquals(2L, counts.get("COLLECTED"));
        assertEquals(List.of("/a/second.txt", "/a/first.txt"),
                store.findRecentlyCollected(10).stream().map(FileRecord::absolutePath).toList());
    }

    @Test
    void openingLegacyDatabaseRebuildsMetadataOnlySchemaAndPurgesDerivedContent() throws Exception {
        Path legacyDb = dbPath.resolveSibling("legacy-file-watch.db");
        String forbidden = "SELF_ANALYST_FORBIDDEN_DERIVED_FILE_CONTENT_43A1";
        createLegacyDatabase(legacyDb, forbidden, 1);

        try (FileWatchStore migrated = new FileWatchStore(legacyDb);
             var conn = DriverManager.getConnection("jdbc:sqlite:" + legacyDb.toAbsolutePath());
             var version = conn.createStatement().executeQuery("PRAGMA user_version")) {
            assertEquals(2, version.getInt(1));
            Set<String> columns = new HashSet<>();
            try (var rs = conn.createStatement().executeQuery("PRAGMA table_info(file_metadata)")) {
                while (rs.next()) columns.add(rs.getString("name"));
            }
            assertTrue(columns.containsAll(Set.of(
                    "absolute_path", "relative_path", "watch_root", "extension", "size_bytes",
                    "file_created_at", "last_modified", "first_seen_at", "last_collected_at")));
            assertTrue(Set.of("file_hash", "summary", "main_topics_json", "model", "prompt_version")
                    .stream().noneMatch(columns::contains));
            assertEquals(FileStatus.COLLECTED, migrated.findByPath("/a/secret.txt").status());
        }

        for (Path path : List.of(legacyDb, Path.of(legacyDb + "-wal"), Path.of(legacyDb + "-shm"))) {
            if (!Files.exists(path)) continue;
            String bytes = new String(Files.readAllBytes(path), StandardCharsets.ISO_8859_1);
            assertFalse(bytes.contains(forbidden), "legacy derived content remains in " + path);
        }
    }

    @Test
    void unversionedLegacyTableCannotBypassMetadataOnlyMigration() throws Exception {
        Path legacyDb = dbPath.resolveSibling("unversioned-legacy.db");
        createLegacyDatabase(legacyDb, "LEGACY_SUMMARY_MUST_BE_REMOVED", 0);

        try (FileWatchStore migrated = new FileWatchStore(legacyDb);
             var conn = DriverManager.getConnection("jdbc:sqlite:" + legacyDb.toAbsolutePath())) {
            assertEquals(FileStatus.COLLECTED, migrated.findByPath("/a/secret.txt").status());
            try (var rs = conn.createStatement().executeQuery(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='file_index'")) {
                assertEquals(0, rs.getInt(1));
            }
        }
    }

    @Test
    void interruptedCompactionResumesBeforeMigrationIsMarkedComplete() throws Exception {
        Path legacyDb = dbPath.resolveSibling("interrupted-legacy.db");
        String forbidden = "INTERRUPTED_LEGACY_DERIVED_CONTENT";
        createLegacyDatabase(legacyDb, forbidden, 1);

        assertThrows(RuntimeException.class, () -> new FileWatchStore(legacyDb,
                () -> { throw new IllegalStateException("simulated interruption before VACUUM"); }));

        try (var conn = DriverManager.getConnection("jdbc:sqlite:" + legacyDb.toAbsolutePath());
             var version = conn.createStatement().executeQuery("PRAGMA user_version")) {
            assertEquals(1, version.getInt(1), "migration must remain resumable until VACUUM succeeds");
            try (var oldTable = conn.createStatement().executeQuery(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='file_index'")) {
                assertEquals(0, oldTable.getInt(1));
            }
        }

        try (FileWatchStore resumed = new FileWatchStore(legacyDb);
             var conn = DriverManager.getConnection("jdbc:sqlite:" + legacyDb.toAbsolutePath());
             var version = conn.createStatement().executeQuery("PRAGMA user_version")) {
            assertEquals(2, version.getInt(1));
            assertEquals(FileStatus.COLLECTED, resumed.findByPath("/a/secret.txt").status());
        }
        String bytes = new String(Files.readAllBytes(legacyDb), StandardCharsets.ISO_8859_1);
        assertFalse(bytes.contains(forbidden));
    }

    private static void createLegacyDatabase(Path path, String forbidden, int version) throws Exception {
        try (var conn = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
             var stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE file_index (
                  id INTEGER PRIMARY KEY AUTOINCREMENT, absolute_path TEXT NOT NULL UNIQUE,
                  relative_path TEXT, watch_root TEXT, extension TEXT,
                  size_bytes INTEGER NOT NULL DEFAULT 0, file_hash TEXT, last_modified TEXT,
                  first_seen_at TEXT NOT NULL, last_indexed_at TEXT, status TEXT NOT NULL,
                  summary TEXT, main_topics_json TEXT, model TEXT, prompt_version TEXT,
                  retry_count INTEGER NOT NULL DEFAULT 0, next_retry_at TEXT, last_error TEXT,
                  created_at TEXT NOT NULL, updated_at TEXT NOT NULL)
                """);
            String now = "2026-08-30T01:00:00Z";
            try (var ps = conn.prepareStatement("""
                    INSERT INTO file_index
                      (absolute_path, relative_path, watch_root, extension, size_bytes,
                       file_hash, last_modified, first_seen_at, last_indexed_at, status,
                       summary, main_topics_json, model, prompt_version, created_at, updated_at)
                    VALUES (?, ?, ?, ?, 42, ?, ?, ?, ?, 'INDEXED', ?, ?, ?, ?, ?, ?)
                    """)) {
                ps.setString(1, "/a/secret.txt");
                ps.setString(2, "secret.txt");
                ps.setString(3, "/a");
                ps.setString(4, "txt");
                ps.setString(5, forbidden);
                ps.setString(6, now);
                ps.setString(7, now);
                ps.setString(8, now);
                ps.setString(9, forbidden);
                ps.setString(10, "[\"" + forbidden + "\"]");
                ps.setString(11, forbidden);
                ps.setString(12, forbidden);
                ps.setString(13, now);
                ps.setString(14, now);
                ps.executeUpdate();
            }
            stmt.execute("PRAGMA user_version=" + version);
        }
    }
}
