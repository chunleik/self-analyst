package com.selfanalyst.events.raw;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RawEventAppendOnlyTest {

    private static final Pattern RAW_MUTATION = Pattern.compile(
            "(?is)\\b(?:UPDATE\\s+raw_events|DELETE\\s+FROM\\s+raw_events)\\b");

    @Test
    void businessAppenderExposesOnlyAppendOperations() {
        assertEquals(java.util.Set.of("append", "appendBatch"),
                Arrays.stream(RawEventAppender.class.getDeclaredMethods())
                        .map(java.lang.reflect.Method::getName)
                        .collect(java.util.stream.Collectors.toSet()));
        assertFalse(Arrays.stream(RawEventAppender.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName)
                .anyMatch(name -> name.startsWith("update") || name.startsWith("delete")));
    }

    @Test
    void databaseRejectsUpdateAndDeleteEvenThroughDirectMaintenanceConnection(
            @TempDir Path dir) throws Exception {
        Instant received = Instant.parse("2026-09-03T12:00:00Z");
        Path partition;
        try (RawEventStore store = new RawEventStore(dir)) {
            store.append(RawEvent.create(new RawEventIdGenerator(), "source-1", "bucket",
                    RawEventSource.CONTENT, 1, RawIngestKind.HEARTBEAT,
                    received, received, 1, Map.of("title", "只追加"), null, null));
            partition = store.partitionPath(YearMonth.of(2026, 9));
        }

        try (var connection = DriverManager.getConnection(
                "jdbc:sqlite:" + partition.toAbsolutePath());
             var statement = connection.createStatement()) {
            assertThrows(SQLException.class,
                    () -> statement.executeUpdate("UPDATE raw_events SET duration = 2"));
            assertThrows(SQLException.class,
                    () -> statement.executeUpdate("DELETE FROM raw_events"));
            try (var result = statement.executeQuery("SELECT COUNT(*) FROM raw_events")) {
                assertEquals(1, result.getLong(1));
            }
        }
    }

    @Test
    void productionSourcesContainNoRawEventUpdateOrDeleteSql() throws Exception {
        Path base = Path.of("").toAbsolutePath();
        Path sourceRoot = base.resolve("src/main/java");
        if (!Files.isDirectory(sourceRoot)) {
            sourceRoot = base.resolve("self-analyst-events/src/main/java");
        }
        try (var files = Files.walk(sourceRoot)) {
            for (Path source : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String text = Files.readString(source);
                assertFalse(RAW_MUTATION.matcher(text).find(), source.toString());
            }
        }
    }
}
