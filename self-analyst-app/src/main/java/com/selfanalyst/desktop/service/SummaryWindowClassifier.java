package com.selfanalyst.desktop.service;

import com.selfanalyst.wiki.WikiLevel;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Classifies Agent timeline slots as open current windows or closed periods.
 */
public class SummaryWindowClassifier {

    public record Slot(
            String key,
            String label,
            Instant start,
            Instant end,
            boolean open,
            boolean span,
            WikiLevel wikiLevel) {
    }

    private final Clock clock;

    public SummaryWindowClassifier() {
        this(Clock.systemDefaultZone());
    }

    public SummaryWindowClassifier(Clock clock) {
        this.clock = clock;
    }

    public Instant now() {
        return clock.instant();
    }

    public ZoneId zone() {
        return clock.getZone();
    }

    public boolean isOpen(String key) {
        return slots().stream().anyMatch(slot -> slot.key().equals(key) && slot.open());
    }

    public List<Slot> slots() {
        return slots(clock.instant());
    }

    public List<Slot> slots(Instant now) {
        ZoneId zone = zone();
        ZonedDateTime localNow = now.atZone(zone);
        LocalDate today = localNow.toLocalDate();
        Instant todayStart = today.atStartOfDay(zone).toInstant();

        List<Slot> slots = new ArrayList<>();
        slots.add(new Slot("current", "当前",
                now.minus(Duration.ofHours(2)), now, true, false, null));
        slots.add(new Slot("today", "今天", todayStart, now, true, false, null));

        if (localNow.getHour() >= 12) {
            Instant morningEnd = today.atTime(LocalTime.NOON).atZone(zone).toInstant();
            slots.add(new Slot("morning", "上午", todayStart, morningEnd, false, false, WikiLevel.HALF_DAY));
        }

        LocalDate yesterday = today.minusDays(1);
        slots.add(closedDay("yesterday", "昨天", yesterday, zone));
        slots.add(closedDay("dayBefore", "前天", today.minusDays(2), zone));

        LocalDate monday = today.with(DayOfWeek.MONDAY);
        slots.add(new Slot("thisWeek", "本周",
                monday.atStartOfDay(zone).toInstant(), now, false, true, WikiLevel.WEEK));
        slots.add(new Slot("lastTwoWeeks", "最近两周",
                now.minus(Duration.ofDays(14)), now, false, true, WikiLevel.BIWEEK));
        slots.add(new Slot("thisMonth", "本月",
                today.withDayOfMonth(1).atStartOfDay(zone).toInstant(), now, false, true, WikiLevel.MONTH));
        return List.copyOf(slots);
    }

    private static Slot closedDay(String key, String label, LocalDate day, ZoneId zone) {
        Instant start = day.atStartOfDay(zone).toInstant();
        Instant end = day.plusDays(1).atStartOfDay(zone).toInstant();
        return new Slot(key, label, start, end, false, false, WikiLevel.DAY);
    }
}
