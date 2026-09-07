package com.selfanalyst.events.projection;

import com.selfanalyst.events.store.Database;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.YearMonth;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectionCheckpointStoreTest {

    @Test
    void projectionSchemaIsIdempotentAndContainsVersionedCoverageTables(@TempDir Path dir)
            throws Exception {
        try (Database ignored = new Database(dir)) {
            // first initialization
        }
        try (Database reopened = new Database(dir);
             var statement = reopened.metaConnection().createStatement();
             var tables = statement.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type = 'table'")) {
            Set<String> names = new HashSet<>();
            while (tables.next()) names.add(tables.getString(1));
            assertTrue(names.contains("raw_projection_sources"));
            assertTrue(names.contains("projection_event_coverage"));
            assertTrue(names.contains("raw_projection_checkpoints"));
        }
    }

    @Test
    void checkpointRollsBackAndAdvancesOnlyWithProjectionTransaction(@TempDir Path dir)
            throws Exception {
        YearMonth month = YearMonth.of(2026, 9);
        Instant first = Instant.parse("2026-09-03T12:00:00Z");
        try (Database database = new Database(dir)) {
            var connection = database.metaConnection();
            ProjectionCheckpointStore checkpoints = new ProjectionCheckpointStore(connection);
            connection.setAutoCommit(false);
            checkpoints.advance(month, first, "01-FIRST", "projector-v1");
            connection.rollback();
            connection.setAutoCommit(true);
            assertTrue(checkpoints.find(month).isEmpty());

            connection.setAutoCommit(false);
            checkpoints.advance(month, first, "01-FIRST", "projector-v1");
            connection.commit();
            connection.setAutoCommit(true);
            assertEquals("01-FIRST", checkpoints.find(month).orElseThrow().eventId());

            connection.setAutoCommit(false);
            checkpoints.advance(month, first.minusSeconds(1), "00-OLDER", "projector-v2");
            connection.commit();
            connection.setAutoCommit(true);
            assertEquals("01-FIRST", checkpoints.find(month).orElseThrow().eventId(),
                    "checkpoint 不得倒退");
        }
    }
}
