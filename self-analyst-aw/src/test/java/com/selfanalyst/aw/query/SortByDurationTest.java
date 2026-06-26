package com.selfanalyst.aw.query;

import com.selfanalyst.aw.model.Event;
import com.selfanalyst.aw.query.function.SortByDuration;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SortByDurationTest {

    @Test
    void shouldSortByDurationDescending() {
        var func = new SortByDuration();
        List<Event> events = List.of(
                new Event(Instant.parse("2026-06-03T10:00:00Z"), 30.0, Map.of("app", "chrome")),
                new Event(Instant.parse("2026-06-03T10:01:00Z"), 120.0, Map.of("app", "firefox")),
                new Event(Instant.parse("2026-06-03T10:02:00Z"), 60.0, Map.of("app", "terminal"))
        );

        List<Event> result = func.apply(events, Map.of(), null);

        assertEquals(120.0, result.get(0).duration());
        assertEquals(60.0, result.get(1).duration());
        assertEquals(30.0, result.get(2).duration());
    }

    @Test
    void shouldHandleEmptyList() {
        var func = new SortByDuration();
        List<Event> result = func.apply(List.of(), Map.of(), null);
        assertTrue(result.isEmpty());
    }
}
