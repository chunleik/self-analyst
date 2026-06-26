package com.selfanalyst.wiki;

import java.time.*;
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

        ZonedDateTime cursor = rangeStart.atZone(timezone);
        ZonedDateTime endZ = rangeEnd.atZone(timezone);

        while (cursor.isBefore(endZ)) {
            ZonedDateTime blockStart = alignStart(cursor, level);
            ZonedDateTime blockEnd = blockEnd(blockStart, level);

            if (blockEnd.toInstant().isAfter(now)) {
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
        return switch (level) {
            case HOUR -> t.truncatedTo(ChronoUnit.HOURS);
            case HALF_DAY -> {
                int hour = t.getHour() < 12 ? 0 : 12;
                yield t.truncatedTo(ChronoUnit.DAYS).withHour(hour);
            }
            case DAY -> t.truncatedTo(ChronoUnit.DAYS);
            case WEEK -> t.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    .truncatedTo(ChronoUnit.DAYS);
            case BIWEEK -> {
                ZonedDateTime weekStart = t.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                        .truncatedTo(ChronoUnit.DAYS);
                long epochWeek = weekStart.toEpochSecond() / (7 * 24 * 3600);
                if (epochWeek % 2 != 0) {
                    yield weekStart.minusWeeks(1);
                }
                yield weekStart;
            }
            case MONTH -> t.withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS);
        };
    }

    private static ZonedDateTime blockEnd(ZonedDateTime blockStart, WikiLevel level) {
        return switch (level) {
            case HOUR -> blockStart.plusHours(1);
            case HALF_DAY -> blockStart.plusHours(12);
            case DAY -> blockStart.plusDays(1);
            case WEEK -> blockStart.plusWeeks(1);
            case BIWEEK -> blockStart.plusWeeks(2);
            case MONTH -> blockStart.plusMonths(1);
        };
    }
}
