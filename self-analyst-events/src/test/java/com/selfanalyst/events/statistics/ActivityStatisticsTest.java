package com.selfanalyst.events.statistics;

import com.selfanalyst.events.model.Event;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ActivityStatisticsTest {
    private final Instant start = Instant.parse("2026-09-14T20:00:00Z");
    private Event window(double offset, double seconds, String app, String title) {
        return new Event(start.plusNanos(Math.round(offset * 1e9)), seconds, Map.of("app", app, "title", title));
    }
    private Event afk(int offset, int seconds, String status) {
        return new Event(start.plusSeconds(offset), seconds, Map.of("status", status));
    }

    @Test void clipsCrossBoundaryAndSubtractsUnion() {
        var result = ActivityStatistics.compute(List.of(window(-600, 4200, "editor", "unknown")),
                List.of(afk(-10, 610, "afk"), afk(300, 900, "afk"), afk(1200, 2400, "not-afk")),
                start, start.plusSeconds(3600));
        assertEquals(2400, result.apps().get("editor"));
        assertEquals(1200, result.afkSeconds());
        assertEquals(2400, result.activeSeconds());
        assertTrue(result.titles().isEmpty());
        assertFalse(result.estimated());
    }

    @Test void unknownAndMissingCoverageRemainExplicit() {
        var result = ActivityStatistics.compute(List.of(window(0, 10, "unknown", "unknown")),
                List.of(afk(0, 5, "afk"), afk(5, 5, "invalid")), start, start.plusSeconds(10));
        assertTrue(result.apps().isEmpty());
        assertEquals(5, result.unknownSeconds());
        assertEquals(5, result.uncoveredSeconds());
        assertEquals(1, result.switchCount());
    }

    @Test void duplicatesAndConflictsDoNotDoubleCount() {
        var result = ActivityStatistics.compute(List.of(window(0, 10, "A", "a"), window(0, 10, "A", "a"),
                        window(5, 10, "B", "b")), List.of(afk(0, 15, "not-afk")), start, start.plusSeconds(20));
        assertEquals(15, result.activeSeconds());
        assertEquals(5, result.unknownSeconds());
        assertEquals(5, result.apps().get("A"));
        assertEquals(5, result.apps().get("B"));
        assertEquals(5, result.conflictSeconds());
    }

    @Test void subsecondEventsAccumulateBeforeRounding() {
        var result = ActivityStatistics.compute(List.of(window(0, .6, "A", ""), window(.6, .6, "A", "")),
                List.of(), start, start.plusSeconds(2));
        assertEquals(1.2, result.activeSeconds(), 1e-9);
    }

    @Test void fullyAfkDoesNotProduceActivity() {
        var result = ActivityStatistics.compute(List.of(window(0, 60, "unknown", "")),
                List.of(afk(0, 60, "afk")), start, start.plusSeconds(60));
        assertEquals(0, result.activeSeconds());
        assertEquals(0, result.unknownSeconds());
        assertEquals(0, result.switchCount());
    }
}
