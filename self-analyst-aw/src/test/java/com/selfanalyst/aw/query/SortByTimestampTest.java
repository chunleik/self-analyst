package com.selfanalyst.aw.query;

import com.selfanalyst.aw.model.Event;
import com.selfanalyst.aw.query.function.SortByTimestamp;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SortByTimestampTest {

    @Test
    void shouldSortByTimestampAscending() {
        var func = new SortByTimestamp();
        List<Event> events = List.of(
                new Event(Instant.parse("2026-06-03T10:02:00Z"), 60.0, Map.of("app", "terminal")),
                new Event(Instant.parse("2026-06-03T10:00:00Z"), 30.0, Map.of("app", "chrome")),
                new Event(Instant.parse("2026-06-03T10:01:00Z"), 120.0, Map.of("app", "firefox"))
        );

        List<Event> result = func.apply(events, Map.of(), null);

        assertEquals("chrome", result.get(0).data().get("app"));
        assertEquals("firefox", result.get(1).data().get("app"));
        assertEquals("terminal", result.get(2).data().get("app"));
    }

    @Test
    void shouldHandleEmptyList() {
        var func = new SortByTimestamp();
        List<Event> result = func.apply(List.of(), Map.of(), null);
        assertTrue(result.isEmpty());
    }
}
