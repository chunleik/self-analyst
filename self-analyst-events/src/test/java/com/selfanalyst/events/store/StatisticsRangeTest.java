package com.selfanalyst.events.store;

import com.selfanalyst.events.model.Event;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StatisticsRangeTest {
    @TempDir Path dir;

    @Test void includesLongEventsWithoutLimitAndKeepsGenericQuery() throws Exception {
        try (Database db = new Database(dir)) {
            EventStore store = new EventStore(db, PulseTimeConfig.DEFAULT);
            Instant start = Instant.parse("2026-09-15T00:00:00.500Z"), end = start.plusSeconds(1);
            store.insertEvent("window", new Event(start.minusSeconds(36000), 36001, Map.of("app", "A")));
            store.insertEvent("window", new Event(end, 10, Map.of("app", "end")));
            store.insertEvent("window", new Event(start, 0, Map.of("app", "zero")));
            var conn = db.metaConnection();
            conn.setAutoCommit(false);
            try (var ps = conn.prepareStatement("INSERT INTO events(bucket_id,timestamp,duration,datastr,app) VALUES('window',?,0.1,'{}','A')")) {
                ps.setString(1, start.toString());
                for (int i = 0; i < 50001; i++) { ps.addBatch(); }
                ps.executeBatch();
            }
            conn.commit(); conn.setAutoCommit(true);
            var data = store.queryIntersecting(List.of("window", "absent"), start, end);
            assertEquals(50002, data.get("window").events().size());
            assertEquals("missing", data.get("absent").status());
            assertTrue(data.get("window").events().stream().noneMatch(e -> "end".equals(e.data().get("app"))));
            assertTrue(store.queryEvents("window", 5, start.toString(), end.toString()).stream()
                    .noneMatch(e -> e.timestamp().isBefore(start)));
            try (var ps = conn.prepareStatement("EXPLAIN QUERY PLAN SELECT * FROM events WHERE bucket_id=? AND timestamp<? AND duration>0 ORDER BY timestamp,id")) {
                ps.setString(1, "window"); ps.setString(2, end.toString());
                try (var rows = ps.executeQuery()) {
                    assertTrue(rows.next());
                    assertTrue(rows.getString("detail").contains("idx_events_bucket_timestamp"));
                }
            }
        }
    }
}
