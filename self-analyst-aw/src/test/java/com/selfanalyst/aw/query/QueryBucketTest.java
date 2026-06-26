package com.selfanalyst.aw.query;

import com.selfanalyst.aw.model.Event;
import com.selfanalyst.aw.query.function.QueryBucket;
import com.selfanalyst.aw.store.BucketStore;
import com.selfanalyst.aw.store.EventStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class QueryBucketTest {

    @Test
    void shouldReturnEventsFromBucket() {
        var store = new FakeEventStore(List.of(
                new Event(Instant.parse("2026-06-03T10:00:00Z"), 60.0, Map.of("app", "firefox")),
                new Event(Instant.parse("2026-06-03T10:01:00Z"), 30.0, Map.of("app", "chrome"))
        ));
        var ctx = new AqlContext(store, new FakeBucketStore(List.of()), null, null);
        var func = new QueryBucket();

        List<Event> result = func.apply(List.of(), Map.of("0", "aw-watcher-window"), ctx);

        assertEquals(2, result.size());
        assertEquals("firefox", result.get(0).data().get("app"));
    }

    @Test
    void shouldReturnEmptyForMissingBucketId() {
        var ctx = new AqlContext(null, null, null, null);
        var func = new QueryBucket();

        List<Event> result = func.apply(List.of(), Map.of("0", ""), ctx);

        assertTrue(result.isEmpty());
    }
}
