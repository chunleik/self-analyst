package com.selfanalyst.events.query;

import com.selfanalyst.events.model.Event;
import com.selfanalyst.events.query.function.Concat;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConcatTest {

    @Test
    void shouldConcatenateTwoEventLists() {
        var func = new Concat();
        List<Event> events1 = List.of(
                new Event(Instant.parse("2026-06-03T10:00:00Z"), 60.0, Map.of("app", "firefox"))
        );
        List<Event> events2 = List.of(
                new Event(Instant.parse("2026-06-03T10:01:00Z"), 30.0, Map.of("app", "chrome"))
        );

        List<Event> result = func.apply(events1, Map.of("0", events2), null);

        assertEquals(2, result.size());
        assertEquals("firefox", result.get(0).data().get("app"));
        assertEquals("chrome", result.get(1).data().get("app"));
    }

    @Test
    void shouldHandleEmptyFirstList() {
        var func = new Concat();
        List<Event> events2 = List.of(
                new Event(Instant.parse("2026-06-03T10:00:00Z"), 60.0, Map.of("app", "firefox"))
        );

        List<Event> result = func.apply(List.of(), Map.of("0", events2), null);

        assertEquals(1, result.size());
    }

    @Test
    void shouldHandleBothEmpty() {
        var func = new Concat();
        List<Event> result = func.apply(List.of(), Map.of("0", List.of()), null);
        assertTrue(result.isEmpty());
    }
}
