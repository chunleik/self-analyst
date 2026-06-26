package com.selfanalyst.aw.query;

import com.selfanalyst.aw.model.Event;
import com.selfanalyst.aw.query.function.LimitEvents;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LimitEventsTest {

    @Test
    void shouldLimitToFirstN() {
        var func = new LimitEvents();
        List<Event> events = List.of(
                new Event(Instant.parse("2026-06-03T10:00:00Z"), 60.0, Map.of("app", "firefox")),
                new Event(Instant.parse("2026-06-03T10:01:00Z"), 30.0, Map.of("app", "chrome")),
                new Event(Instant.parse("2026-06-03T10:02:00Z"), 45.0, Map.of("app", "terminal"))
        );

        List<Event> result = func.apply(events, Map.of("0", 2), null);

        assertEquals(2, result.size());
        assertEquals("firefox", result.get(0).data().get("app"));
        assertEquals("chrome", result.get(1).data().get("app"));
    }

    @Test
    void shouldReturnAllIfLimitExceedsSize() {
        var func = new LimitEvents();
        List<Event> events = List.of(
                new Event(Instant.parse("2026-06-03T10:00:00Z"), 60.0, Map.of("app", "firefox"))
        );

        List<Event> result = func.apply(events, Map.of("0", 10), null);

        assertEquals(1, result.size());
    }

    @Test
    void shouldReturnEmptyForNegativeLimit() {
        var func = new LimitEvents();
        List<Event> events = List.of(
                new Event(Instant.parse("2026-06-03T10:00:00Z"), 60.0, Map.of("app", "firefox"))
        );

        List<Event> result = func.apply(events, Map.of("0", -1), null);

        assertTrue(result.isEmpty());
    }
}
