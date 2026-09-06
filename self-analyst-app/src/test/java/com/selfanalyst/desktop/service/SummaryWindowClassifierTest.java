package com.selfanalyst.desktop.service;

import com.selfanalyst.wiki.WikiLevel;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SummaryWindowClassifierTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Test
    void morningIsOpenWindowOnlyBeforeNoon() {
        SummaryWindowClassifier morning = classifier("2026-09-06T02:30:00Z"); // 10:30 CST
        Map<String, SummaryWindowClassifier.Slot> morningSlots = index(morning);
        assertTrue(morningSlots.get("current").open());
        assertTrue(morningSlots.get("today").open());
        assertFalse(morningSlots.containsKey("morning"));
        assertFalse(morningSlots.get("yesterday").open());
        assertFalse(morningSlots.get("dayBefore").open());
        assertTrue(morningSlots.get("thisWeek").span());

        SummaryWindowClassifier afternoon = classifier("2026-09-06T06:00:00Z"); // 14:00 CST
        Map<String, SummaryWindowClassifier.Slot> afternoonSlots = index(afternoon);
        assertTrue(afternoonSlots.containsKey("morning"));
        assertFalse(afternoonSlots.get("morning").open());
        assertEquals(WikiLevel.HALF_DAY, afternoonSlots.get("morning").wikiLevel());
        assertEquals(WikiLevel.DAY, afternoonSlots.get("yesterday").wikiLevel());
    }

    @Test
    void crossingMidnightMovesTodayToYesterday() {
        SummaryWindowClassifier before = classifier("2026-09-05T15:30:00Z"); // 23:30 Sep 5
        LocalDate sep5 = LocalDate.of(2026, 9, 5);
        assertEquals(sep5, before.slots().stream()
                .filter(slot -> "today".equals(slot.key()))
                .findFirst()
                .orElseThrow()
                .start()
                .atZone(ZONE)
                .toLocalDate());

        SummaryWindowClassifier after = classifier("2026-09-05T16:30:00Z"); // 00:30 Sep 6
        SummaryWindowClassifier.Slot yesterday = after.slots().stream()
                .filter(slot -> "yesterday".equals(slot.key()))
                .findFirst()
                .orElseThrow();
        assertEquals(sep5, yesterday.start().atZone(ZONE).toLocalDate());
        assertFalse(yesterday.open());
    }

    private static SummaryWindowClassifier classifier(String instant) {
        return new SummaryWindowClassifier(Clock.fixed(Instant.parse(instant), ZONE));
    }

    private static Map<String, SummaryWindowClassifier.Slot> index(SummaryWindowClassifier classifier) {
        return classifier.slots().stream()
                .collect(Collectors.toMap(SummaryWindowClassifier.Slot::key, Function.identity()));
    }
}
