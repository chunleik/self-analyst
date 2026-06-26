package com.selfanalyst.aw.query;

import com.selfanalyst.aw.model.Event;
import com.selfanalyst.aw.query.function.HeartbeatMerge;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HeartbeatMergeTest {

    @Test
    void shouldMergeEventsWithinPulseTime() {
        var func = new HeartbeatMerge();
        List<Event> events = List.of(
                new Event(Instant.parse("2026-06-03T10:00:00Z"), 5.0, Map.of("status", "active")),
                new Event(Instant.parse("2026-06-03T10:00:10Z"), 5.0, Map.of("status", "active")),
                new Event(Instant.parse("2026-06-03T10:00:20Z"), 5.0, Map.of("status", "active"))
        );

        List<Event> result = func.apply(events, Map.of("0", 30.0), null);

        assertEquals(1, result.size());
        assertEquals("active", result.getFirst().data().get("status"));
    }

    @Test
    void shouldNotMergeEventsBeyondPulseTime() {
        var func = new HeartbeatMerge();
        List<Event> events = List.of(
                new Event(Instant.parse("2026-06-03T10:00:00Z"), 5.0, Map.of("status", "active")),
                new Event(Instant.parse("2026-06-03T10:01:00Z"), 5.0, Map.of("status", "active"))
        );

        List<Event> result = func.apply(events, Map.of("0", 10.0), null);

        assertEquals(2, result.size());
    }

    @Test
    void shouldNotMergeDifferentData() {
        var func = new HeartbeatMerge();
        List<Event> events = List.of(
                new Event(Instant.parse("2026-06-03T10:00:00Z"), 5.0, Map.of("status", "active")),
                new Event(Instant.parse("2026-06-03T10:00:10Z"), 5.0, Map.of("status", "afk"))
        );

        List<Event> result = func.apply(events, Map.of("0", 30.0), null);

        assertEquals(2, result.size());
    }

    @Test
    void shouldHandleEmptyInput() {
        var func = new HeartbeatMerge();
        List<Event> result = func.apply(List.of(), Map.of("0", 30.0), null);
        assertTrue(result.isEmpty());
    }
}
