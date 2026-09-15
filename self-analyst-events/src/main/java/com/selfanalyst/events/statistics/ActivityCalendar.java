package com.selfanalyst.events.statistics;

import java.time.*;

/** 本地统计日从 04:00 开始；日历边界与经过时长分开计算。 */
public final class ActivityCalendar {
    public static final String VERSION = "day-0400-v1";
    public static final LocalTime DAY_START = LocalTime.of(4, 0);

    private ActivityCalendar() {}

    public static LocalDate date(Instant instant, ZoneId zone) {
        ZonedDateTime local = instant.atZone(zone);
        return local.toLocalTime().isBefore(DAY_START)
                ? local.toLocalDate().minusDays(1) : local.toLocalDate();
    }

    public static Instant start(LocalDate date, ZoneId zone) {
        return date.atTime(DAY_START).atZone(zone).toInstant();
    }

    public static Instant start(Instant instant, ZoneId zone) {
        return start(date(instant, zone), zone);
    }

    public static Instant noon(LocalDate date, ZoneId zone) {
        return date.atTime(LocalTime.NOON).atZone(zone).toInstant();
    }

    public static Instant weekStart(Instant instant, ZoneId zone) {
        return start(date(instant, zone).with(DayOfWeek.MONDAY), zone);
    }

    public static Instant monthStart(Instant instant, ZoneId zone) {
        return start(date(instant, zone).withDayOfMonth(1), zone);
    }
}
