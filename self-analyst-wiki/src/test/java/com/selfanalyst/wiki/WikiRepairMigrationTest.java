package com.selfanalyst.wiki;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WikiRepairMigrationTest {
    @TempDir Path directory;

    private Path version4() throws Exception {
        Path path = directory.resolve("wiki.db");
        try (WikiStore store = new WikiStore(path)) { store.upsert(WikiRepairServiceTest.entry("target")); }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path); var statement = connection.createStatement()) {
            statement.execute("DROP TRIGGER wiki_entries_revision");
            statement.execute("ALTER TABLE wiki_entries DROP COLUMN revision");
            for (String table : List.of("wiki_repair_drafts", "wiki_repair_statements", "wiki_repair_revisions", "wiki_repair_followups"))
                statement.execute("DROP TABLE " + table);
            statement.execute("PRAGMA user_version=4");
        }
        return path;
    }

    @Test void v4UpgradeRetainsRecordsCreatesIndependentBackupAndIsRepeatable() throws Exception {
        Path path = version4();
        for (int restart = 0; restart < 2; restart++) {
            try (WikiStore store = new WikiStore(path)) {
                assertEquals("target", store.query(null, null, null).getFirst().id());
                assertEquals(0, store.revision("target"));
                assertEquals(900, WikiGenerationProgress.from(store.versionedEntry("target").entry()).get("tokens"));
            }
        }
        Path backup = path.resolveSibling("wiki.db.before-summary-repair-v5.bak"); assertTrue(Files.isRegularFile(backup));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + backup); var statement = connection.createStatement()) {
            try (var row = statement.executeQuery("PRAGMA user_version")) { assertEquals(4, row.getInt(1)); }
            try (var row = statement.executeQuery("SELECT count(*) FROM wiki_entries")) { assertEquals(1, row.getInt(1)); }
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path); var statement = connection.createStatement();
             var row = statement.executeQuery("PRAGMA user_version")) { assertEquals(5, row.getInt(1)); }
    }

    @Test void failedMigrationRollsBackColumnTablesAndVersionWithoutTouchingBackup() throws Exception {
        Path path = version4();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE wiki_repair_statements(block INTEGER)");
        }
        assertThrows(RuntimeException.class, () -> new WikiStore(path));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path); var statement = connection.createStatement()) {
            try (var row = statement.executeQuery("PRAGMA user_version")) { assertEquals(4, row.getInt(1)); }
            try (var row = statement.executeQuery("SELECT count(*) FROM pragma_table_info('wiki_entries') WHERE name='revision'")) { assertEquals(0, row.getInt(1)); }
            try (var row = statement.executeQuery("SELECT count(*) FROM sqlite_master WHERE name='wiki_repair_drafts'")) { assertEquals(0, row.getInt(1)); }
            try (var row = statement.executeQuery("SELECT count(*) FROM wiki_entries")) { assertEquals(1, row.getInt(1)); }
        }
        assertTrue(Files.isRegularFile(path.resolveSibling("wiki.db.before-summary-repair-v5.bak")));
    }

    @Test void unknownFutureVersionIsRejected() throws Exception {
        Path path = version4();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path); var statement = connection.createStatement()) { statement.execute("PRAGMA user_version=6"); }
        assertThrows(RuntimeException.class, () -> new WikiStore(path));
        assertFalse(Files.exists(path.resolveSibling("wiki.db.before-summary-repair-v5.bak")));
    }

    @Test void everyMutationIncrementsRevisionIncludingSameTimestampAndUpsert() {
        try (WikiStore store = new WikiStore(directory.resolve("wiki.db"))) {
            var entry = WikiRepairServiceTest.entry("target"); store.upsert(entry); assertEquals(0, store.revision("target"));
            store.upsert(entry); assertEquals(1, store.revision("target"));
            store.markFailed(entry.id(), "WIKI_EVIDENCE_STRUCTURE", entry.periodEnd()); assertEquals(2, store.revision("target"));
            store.markFailed(entry.id(), "WIKI_EVIDENCE_STRUCTURE", entry.periodEnd()); assertEquals(3, store.revision("target"));
        }
    }
    @Test void interruptedOrCorruptBackupNeverAuthorizesMigration() throws Exception {
        Path path = version4();
        Path backup = path.resolveSibling("wiki.db.before-summary-repair-v5.bak");
        Files.writeString(backup, "interrupted VACUUM");
        assertThrows(RuntimeException.class, () -> new WikiStore(path));
        assertEquals("interrupted VACUUM", Files.readString(backup));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path); var statement = connection.createStatement();
             var row = statement.executeQuery("PRAGMA user_version")) { assertEquals(4, row.getInt(1)); }
        Files.delete(backup);
        Files.writeString(path.resolveSibling("wiki.db.before-summary-repair-v5.bak.00000000-0000-0000-0000-000000000000.tmp"), "interrupted staging");
        try (WikiStore store = new WikiStore(path)) { assertEquals(0, store.revision("target")); }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + backup); var statement = connection.createStatement();
             var row = statement.executeQuery("PRAGMA integrity_check")) { assertEquals("ok", row.getString(1)); }
    }

    @Test void validButStaleExistingBackupIsRejectedRatherThanOverwritten() throws Exception {
        Path path = version4(); Path backup = path.resolveSibling("wiki.db.before-summary-repair-v5.bak");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path); var statement = connection.createStatement()) {
            statement.execute("VACUUM INTO '" + backup.toString().replace("'", "''") + "'");
            statement.execute("UPDATE wiki_entries SET retry_count=99 WHERE id='target'");
        }
        byte[] preserved = Files.readAllBytes(backup);
        assertThrows(RuntimeException.class, () -> new WikiStore(path));
        assertArrayEquals(preserved, Files.readAllBytes(backup));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path); var statement = connection.createStatement();
             var row = statement.executeQuery("PRAGMA user_version")) { assertEquals(4, row.getInt(1)); }
    }

}
