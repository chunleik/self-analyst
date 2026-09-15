package com.selfanalyst.events.statistics;

import org.junit.jupiter.api.Test;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

class ActivityCalendarTest {
    @Test void mondayMonthBoundaryAndExactFour() {
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        Instant before = LocalDateTime.parse("2026-06-01T03:59:59").atZone(zone).toInstant();
        assertEquals(LocalDate.parse("2026-05-31"), ActivityCalendar.date(before, zone));
        assertEquals(LocalDate.parse("2026-05-25"), ActivityCalendar.weekStart(before, zone).atZone(zone).toLocalDate());
        assertEquals(LocalDate.parse("2026-05-01"), ActivityCalendar.monthStart(before, zone).atZone(zone).toLocalDate());
        assertEquals(LocalDate.parse("2026-06-01"), ActivityCalendar.date(before.plusSeconds(1), zone));
    }

    @Test void leapDayAndDaylightSavingUseCalendarDays() {
        ZoneId zone = ZoneId.of("America/New_York");
        Instant a = ActivityCalendar.start(LocalDate.parse("2026-03-07"), zone);
        Instant b = ActivityCalendar.start(LocalDate.parse("2026-03-08"), zone);
        assertEquals(23, Duration.between(a, b).toHours());
        a = ActivityCalendar.start(LocalDate.parse("2026-10-31"), zone);
        b = ActivityCalendar.start(LocalDate.parse("2026-11-01"), zone);
        assertEquals(25, Duration.between(a, b).toHours());
        assertEquals(LocalDate.parse("2024-02-29"), ActivityCalendar.date(
                LocalDateTime.parse("2024-03-01T03:00").atZone(zone).toInstant(), zone));
    }
}
