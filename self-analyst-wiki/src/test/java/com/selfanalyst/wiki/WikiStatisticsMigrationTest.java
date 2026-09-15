package com.selfanalyst.wiki;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WikiStatisticsMigrationTest {
    @TempDir Path dir;
    private final Instant start = Instant.parse("2026-09-01T04:00:00Z");

    private WikiEntry entry(String id) {
        return new WikiEntry(id, WikiLevel.HOUR, start, start.plusSeconds(3600), "UTC",
                WikiStatus.SUMMARIZED, "retained summary", "task", List.of(),
                new WikiEntry.WikiMetrics(60, 0, 1, List.of(), Map.of()), List.of(),
                "test", "prompt", 0, null, null, start, start, start,
                "old-facts", "projection", Map.of("window", new WikiEntry.SourceCoverage("complete", start, start.plusSeconds(3600), 0L)));
    }

    private Path legacyDatabase() throws Exception {
        Path path = dir.resolve("wiki.db");
        try (WikiStore store = new WikiStore(path)) { store.upsert(entry("old")); }
        // Recreate the exact v3 column order and uniqueness, including its provenance columns.
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + path); Statement s = c.createStatement()) {
            String schema;
            try (ResultSet rows = s.executeQuery("SELECT sql FROM sqlite_master WHERE name='wiki_entries'")) { rows.next(); schema = rows.getString(1); }
            schema = schema.replace("\"wiki_entries\"", "wiki_entries_old")
                    .replace("statistics_version TEXT NOT NULL DEFAULT 'legacy', calendar_version TEXT NOT NULL DEFAULT 'legacy', ", "")
                    .replace("timezone, statistics_version, calendar_version)", "timezone)");
            s.execute(schema);
            List<String> columns = new ArrayList<>();
            try (ResultSet rows = s.executeQuery("PRAGMA table_info(wiki_entries_old)")) { while (rows.next()) columns.add(rows.getString("name")); }
            s.execute("INSERT INTO wiki_entries_old SELECT " + String.join(",", columns) + " FROM wiki_entries");
            s.execute("DROP TABLE wiki_entries");
            s.execute("ALTER TABLE wiki_entries_old RENAME TO wiki_entries");
            s.execute("PRAGMA user_version=3");
        }
        return path;
    }

    @Test void migrationRetainsOldFactsAndAllowsSameHourAtNewVersion() throws Exception {
        Path path = legacyDatabase();
        try (WikiStore store = new WikiStore(path)) {
            assertTrue(store.query(null, null, null).isEmpty());
            assertFalse(store.isCurrentEntry("old"));
            store.upsert(entry("new"));
            assertEquals(1, store.query(null, null, WikiLevel.HOUR).size());
            assertEquals("new", store.query(null, null, WikiLevel.HOUR).getFirst().id());
            store.saveHistoryBefore(start);
        }
        try (WikiStore reopened = new WikiStore(path)) {
            assertEquals(start, reopened.historyBefore());
            assertEquals(1, reopened.query(null, null, null).size());
        }
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + path); Statement s = c.createStatement();
             ResultSet rows = s.executeQuery("SELECT summary,fact_builder_version,projector_version,source_coverage_json,statistics_version FROM wiki_entries WHERE id='old'")) {
            assertTrue(rows.next());
            assertEquals("retained summary", rows.getString(1));
            assertEquals("old-facts", rows.getString(2));
            assertEquals("projection", rows.getString(3));
            assertTrue(rows.getString(4).contains("complete"));
            assertEquals("legacy", rows.getString(5));
        }
        Path backup = path.resolveSibling("wiki.db.before-statistics-v4.bak");
        assertTrue(Files.isRegularFile(backup));
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + backup); Statement s = c.createStatement();
             ResultSet rows = s.executeQuery("PRAGMA user_version")) {
            assertEquals(3, rows.getInt(1));
        }
    }

    @Test void migrationFailureRollsBackAndLeavesBackup() throws Exception {
        Path path = legacyDatabase();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + path); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE wiki_entries_v4(block INTEGER)");
        }
        assertThrows(RuntimeException.class, () -> new WikiStore(path));
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + path); Statement s = c.createStatement()) {
            try (ResultSet rows = s.executeQuery("PRAGMA user_version")) { assertEquals(3, rows.getInt(1)); }
            try (ResultSet rows = s.executeQuery("SELECT count(*) FROM wiki_entries WHERE id='old'")) { assertEquals(1, rows.getInt(1)); }
        }
    }

    @Test void oldSemanticResultCannotReturnThroughWikiTools() throws Exception {
        Path path = legacyDatabase();
        try (WikiStore store = new WikiStore(path);
             var index = new com.selfanalyst.wiki.semantic.WikiSemanticIndex(dir.resolve("index"), 3)) {
            index.indexDocument("old-doc", "old", "ENTRY_SUMMARY", WikiLevel.HOUR, start, start.plusSeconds(3600),
                    "legacy", "legacy", "legacy", "legacy", new float[]{1, 0, 0});
            var embeddings = new com.selfanalyst.wiki.semantic.EmbeddingClient() {
                @Override public java.util.List<float[]> embed(java.util.List<String> texts) {
                    return texts.stream().map(text -> new float[]{1, 0, 0}).toList();
                }
            };
            WikiTools tools = new WikiTools(store, index, embeddings, 8);
            String result = tools.semanticSearchWiki("legacy", null, null, null, 8);
            assertTrue(result.contains("\"results\":[]"), result);
        }
    }
}
