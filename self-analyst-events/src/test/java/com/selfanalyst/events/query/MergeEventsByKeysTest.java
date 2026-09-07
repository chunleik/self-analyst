package com.selfanalyst.events.query;

import com.selfanalyst.events.model.Event;
import com.selfanalyst.events.query.function.MergeEventsByKeys;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MergeEventsByKeysTest {

    @Test
    void shouldMergeConsecutiveEventsWithSameApp() {
        var func = new MergeEventsByKeys();
        List<Event> events = List.of(
                new Event(Instant.parse("2026-06-03T10:00:00Z"), 60.0, Map.of("app", "firefox", "title", "GitHub")),
                new Event(Instant.parse("2026-06-03T10:01:00Z"), 30.0, Map.of("app", "firefox", "title", "GitHub")),
                new Event(Instant.parse("2026-06-03T10:01:30Z"), 45.0, Map.of("app", "chrome", "title", "Gmail"))
        );

        List<Event> result = func.apply(events, Map.of("0", "app,title"), null);

        assertEquals(2, result.size());
        assertEquals("firefox", result.get(0).data().get("app"));
        // merged duration: from 10:00:00 to 10:01:30 = 90 seconds
        assertEquals(90.0, result.get(0).duration(), 0.001);
    }

    @Test
    void shouldNotMergeDifferentApps() {
        var func = new MergeEventsByKeys();
        List<Event> events = List.of(
                new Event(Instant.parse("2026-06-03T10:00:00Z"), 60.0, Map.of("app", "firefox")),
                new Event(Instant.parse("2026-06-03T10:01:00Z"), 30.0, Map.of("app", "chrome"))
        );

        List<Event> result = func.apply(events, Map.of("0", "app"), null);

        assertEquals(2, result.size());
    }

    @Test
    void shouldHandleEmptyInput() {
        var func = new MergeEventsByKeys();
        List<Event> result = func.apply(List.of(), Map.of("0", "app"), null);
        assertTrue(result.isEmpty());
    }
}
