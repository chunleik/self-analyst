package com.selfanalyst.wiki;

import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WikiPeriodFactoryTest {

    private final ZoneId tz = ZoneId.of("Asia/Shanghai");

    @Test
    void shouldGenerateHourPeriods() {
        Instant start = LocalDateTime.of(2026, 6, 1, 10, 15).atZone(tz).toInstant();
        Instant end = LocalDateTime.of(2026, 6, 1, 13, 30).atZone(tz).toInstant();

        List<WikiPeriod> periods = WikiPeriodFactory.generate(start, end, WikiLevel.HOUR, tz);
        assertFalse(periods.isEmpty());
        assertEquals(WikiLevel.HOUR, periods.get(0).level());
        assertEquals(tz.getId(), periods.get(0).timezone());
        // periods should be exactly 1 hour apart
        for (WikiPeriod p : periods) {
            long duration = p.end().getEpochSecond() - p.start().getEpochSecond();
            assertEquals(3600, duration, "Hour period must be exactly 1 hour");
        }
    }

    @Test
    void shouldGenerateHalfDayPeriods() {
        Instant start = LocalDateTime.of(2026, 6, 1, 8, 0).atZone(tz).toInstant();
        Instant end = LocalDateTime.of(2026, 6, 2, 8, 0).atZone(tz).toInstant();

        List<WikiPeriod> periods = WikiPeriodFactory.generate(start, end, WikiLevel.HALF_DAY, tz);
        assertFalse(periods.isEmpty());
        assertEquals(WikiLevel.HALF_DAY, periods.get(0).level());
        for (WikiPeriod p : periods) {
            long duration = p.end().getEpochSecond() - p.start().getEpochSecond();
            assertEquals(12 * 3600, duration, "Half-day period must be exactly 12 hours");
            int hour = p.start().atZone(tz).getHour();
            assertTrue(hour == 0 || hour == 12, "Half-day must start at 0 or 12, got " + hour);
        }
    }

    @Test
    void shouldGenerateDayPeriods() {
        Instant start = LocalDateTime.of(2026, 6, 1, 10, 0).atZone(tz).toInstant();
        Instant end = LocalDateTime.of(2026, 6, 3, 8, 0).atZone(tz).toInstant();

        List<WikiPeriod> periods = WikiPeriodFactory.generate(start, end, WikiLevel.DAY, tz);
        assertFalse(periods.isEmpty());
        assertEquals(WikiLevel.DAY, periods.get(0).level());
        for (WikiPeriod p : periods) {
            assertEquals(0, p.start().atZone(tz).getHour(), "Day must start at midnight");
        }
    }

    @Test
    void shouldNotIncludeCurrentOpenPeriod() {
        Instant start = Instant.now().minus(Duration.ofHours(3));
        Instant end = Instant.now();

        List<WikiPeriod> periods = WikiPeriodFactory.generate(start, end, WikiLevel.HOUR, tz);
        for (WikiPeriod p : periods) {
            assertFalse(p.end().isAfter(Instant.now()), "Open period included: " + p);
        }
    }

    @Test
    void shouldGenerateWeekPeriods() {
        Instant start = LocalDate.of(2026, 6, 1).atStartOfDay(tz).toInstant();
        Instant end = LocalDate.of(2026, 6, 15).atStartOfDay(tz).toInstant();

        List<WikiPeriod> periods = WikiPeriodFactory.generate(start, end, WikiLevel.WEEK, tz);
        assertFalse(periods.isEmpty());
        assertEquals(WikiLevel.WEEK, periods.get(0).level());
        // Week must start on Monday
        assertEquals(DayOfWeek.MONDAY, periods.get(0).start().atZone(tz).getDayOfWeek());
    }

    @Test
    void shouldGenerateMonthPeriods() {
        Instant start = LocalDate.of(2026, 1, 15).atStartOfDay(tz).toInstant();
        Instant end = LocalDate.of(2026, 4, 1).atStartOfDay(tz).toInstant();

        List<WikiPeriod> periods = WikiPeriodFactory.generate(start, end, WikiLevel.MONTH, tz);
        assertFalse(periods.isEmpty());
        assertEquals(WikiLevel.MONTH, periods.get(0).level());
        // Month must start on day 1
        assertEquals(1, periods.get(0).start().atZone(tz).getDayOfMonth());
    }

    @Test
    void shouldHandleEmptyRange() {
        List<WikiPeriod> periods = WikiPeriodFactory.generate(
                Instant.now(), Instant.now(), WikiLevel.HOUR, tz);
        assertTrue(periods.isEmpty());
    }
}
