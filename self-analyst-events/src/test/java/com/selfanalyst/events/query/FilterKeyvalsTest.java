package com.selfanalyst.events.query;

import com.selfanalyst.events.model.Event;
import com.selfanalyst.events.query.function.FilterKeyvals;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FilterKeyvalsTest {

    @Test
    void shouldFilterEventsByKeyValue() {
        var func = new FilterKeyvals();
        List<Event> events = List.of(
                new Event(Instant.parse("2026-06-03T10:00:00Z"), 60.0, Map.of("app", "firefox")),
                new Event(Instant.parse("2026-06-03T10:01:00Z"), 30.0, Map.of("app", "chrome")),
                new Event(Instant.parse("2026-06-03T10:02:00Z"), 45.0, Map.of("app", "firefox"))
        );

        List<Event> result = func.apply(events, Map.of("0", "app", "1", List.of("firefox")), null);

        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(e -> e.data().get("app").equals("firefox")));
    }

    @Test
    void shouldReturnEmptyWhenNoMatch() {
        var func = new FilterKeyvals();
        List<Event> events = List.of(
                new Event(Instant.parse("2026-06-03T10:00:00Z"), 60.0, Map.of("app", "firefox"))
        );

        List<Event> result = func.apply(events, Map.of("0", "app", "1", List.of("safari")), null);

        assertTrue(result.isEmpty());
    }

    @Test
    void shouldReturnEmptyForMissingKey() {
        var func = new FilterKeyvals();
        List<Event> events = List.of(
                new Event(Instant.parse("2026-06-03T10:00:00Z"), 60.0, Map.of("app", "firefox"))
        );

        List<Event> result = func.apply(events, Map.of("0", ""), null);

        assertTrue(result.isEmpty());
    }

    @Test
    void shouldHandleNullInput() {
        var func = new FilterKeyvals();
        List<Event> result = func.apply(List.of(), Map.of("0", "app", "1", List.of("firefox")), null);
        assertTrue(result.isEmpty());
    }
}
