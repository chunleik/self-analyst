package com.selfanalyst.aw.query;

import com.selfanalyst.aw.model.Event;
import com.selfanalyst.aw.query.function.FilterKeyvalsRegex;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FilterKeyvalsRegexTest {

    @Test
    void shouldFilterByRegexMatch() {
        var func = new FilterKeyvalsRegex();
        List<Event> events = List.of(
                new Event(Instant.parse("2026-06-03T10:00:00Z"), 60.0, Map.of("title", "GitHub - Pull Requests")),
                new Event(Instant.parse("2026-06-03T10:01:00Z"), 30.0, Map.of("title", "Stack Overflow - Questions")),
                new Event(Instant.parse("2026-06-03T10:02:00Z"), 45.0, Map.of("title", "GitHub - Issues"))
        );

        List<Event> result = func.apply(events, Map.of("0", "title", "1", "GitHub"), null);

        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(e -> ((String) e.data().get("title")).contains("GitHub")));
    }

    @Test
    void shouldReturnEmptyForNoMatch() {
        var func = new FilterKeyvalsRegex();
        List<Event> events = List.of(
                new Event(Instant.parse("2026-06-03T10:00:00Z"), 60.0, Map.of("title", "GitHub"))
        );

        List<Event> result = func.apply(events, Map.of("0", "title", "1", "Bitbucket"), null);

        assertTrue(result.isEmpty());
    }

    @Test
    void shouldHandleEmptyRegex() {
        var func = new FilterKeyvalsRegex();
        List<Event> events = List.of(
                new Event(Instant.parse("2026-06-03T10:00:00Z"), 60.0, Map.of("app", "firefox"))
        );

        List<Event> result = func.apply(events, Map.of("0", "app", "1", ""), null);

        assertTrue(result.isEmpty());
    }
}
