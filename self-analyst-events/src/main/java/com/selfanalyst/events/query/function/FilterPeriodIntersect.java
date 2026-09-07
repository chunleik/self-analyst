package com.selfanalyst.events.query.function;

import com.selfanalyst.events.model.Event;
import com.selfanalyst.events.query.AqlFunction;
import com.selfanalyst.events.query.AqlContext;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public class FilterPeriodIntersect implements AqlFunction {
    @Override
    @SuppressWarnings("unchecked")
    public List<Event> apply(List<Event> input, Map<String, Object> params, AqlContext ctx) {
        Object filterRaw = params.get("0");
        List<Event> filterEvents;
        if (filterRaw instanceof List) {
            filterEvents = (List<Event>) filterRaw;
        } else {
            filterEvents = List.of();
        }

        if (input.isEmpty() || filterEvents.isEmpty()) return List.of();

        return input.stream()
                .filter(e -> hasOverlap(e, filterEvents))
                .toList();
    }

    private boolean hasOverlap(Event event, List<Event> filterEvents) {
        Instant eStart = event.timestamp();
        Instant eEnd = eStart.plusMillis((long) (event.duration() * 1000));

        for (Event fe : filterEvents) {
            Instant fStart = fe.timestamp();
            Instant fEnd = fStart.plusMillis((long) (fe.duration() * 1000));

            if (eStart.isBefore(fEnd) && fStart.isBefore(eEnd)) {
                return true;
            }
        }
        return false;
    }
}
