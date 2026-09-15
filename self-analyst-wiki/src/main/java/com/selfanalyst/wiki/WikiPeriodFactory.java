package com.selfanalyst.wiki;

import java.time.*;
import com.selfanalyst.events.statistics.ActivityCalendar;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;

public final class WikiPeriodFactory {

    private WikiPeriodFactory() {}

    public static List<WikiPeriod> generate(Instant rangeStart, Instant rangeEnd,
                                             WikiLevel level, ZoneId timezone) {
        List<WikiPeriod> periods = new ArrayList<>();
        Instant now = Instant.now();
        Instant completionCutoff = rangeEnd.isBefore(now) ? rangeEnd : now;

        ZonedDateTime cursor = rangeStart.atZone(timezone);
        ZonedDateTime endZ = rangeEnd.atZone(timezone);

        while (cursor.isBefore(endZ)) {
            ZonedDateTime blockStart = alignStart(cursor, level);
            ZonedDateTime blockEnd = blockEnd(blockStart, level);

            if (blockEnd.toInstant().isAfter(completionCutoff)) {
                break;
            }
            if (!blockEnd.toInstant().isAfter(rangeStart)) {
                cursor = blockEnd;
                continue;
            }

            periods.add(new WikiPeriod(level, blockStart.toInstant(),
                    blockEnd.toInstant(), timezone.getId()));
            cursor = blockEnd;
        }
        return periods;
    }

    private static ZonedDateTime alignStart(ZonedDateTime t, WikiLevel level) {
        LocalDate day = ActivityCalendar.date(t.toInstant(), t.getZone());
        ZonedDateTime dayStart = ActivityCalendar.start(day, t.getZone()).atZone(t.getZone());
        return switch (level) {
            case HOUR -> t.truncatedTo(ChronoUnit.HOURS);
            case HALF_DAY -> {
                ZonedDateTime noon = day.atTime(12, 0).atZone(t.getZone());
                yield t.isBefore(noon) ? dayStart : noon;
            }
            case DAY -> dayStart;
            case WEEK -> dayStart.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case BIWEEK -> {
                ZonedDateTime weekStart = dayStart.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                long epochWeek = weekStart.toEpochSecond() / (7 * 24 * 3600);
                if (epochWeek % 2 != 0) {
                    yield weekStart.minusWeeks(1);
                }
                yield weekStart;
            }
            case MONTH -> dayStart.withDayOfMonth(1);
        };
    }

    private static ZonedDateTime blockEnd(ZonedDateTime blockStart, WikiLevel level) {
        return switch (level) {
            case HOUR -> blockStart.plusHours(1);
            case HALF_DAY -> blockStart.getHour() == 4 ? blockStart.withHour(12)
                    : ActivityCalendar.start(blockStart.toLocalDate().plusDays(1), blockStart.getZone()).atZone(blockStart.getZone());
            case DAY -> blockStart.plusDays(1);
            case WEEK -> blockStart.plusWeeks(1);
            case BIWEEK -> blockStart.plusWeeks(2);
            case MONTH -> blockStart.plusMonths(1);
        };
    }
}
