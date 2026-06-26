package com.selfanalyst.aw.query.function;

import com.selfanalyst.aw.model.Event;
import com.selfanalyst.aw.query.AqlFunction;
import com.selfanalyst.aw.query.AqlContext;

import java.time.Instant;
import java.util.*;

public class PeriodUnion implements AqlFunction {
    @Override
    public List<Event> apply(List<Event> input, Map<String, Object> params, AqlContext ctx) {
        if (input.isEmpty()) return input;

        List<Event> sorted = new ArrayList<>(input);
        sorted.sort(Comparator.comparing(Event::timestamp));

        List<Event> union = new ArrayList<>();
        Event current = sorted.getFirst();

        for (int i = 1; i < sorted.size(); i++) {
            Event next = sorted.get(i);
            Instant curEnd = current.timestamp().plusMillis((long) (current.duration() * 1000));
            Instant nextEnd = next.timestamp().plusMillis((long) (next.duration() * 1000));

            if (!next.timestamp().isAfter(curEnd)) {
                // Overlapping or adjacent: merge by extending duration
                Instant newEnd = curEnd.isAfter(nextEnd) ? curEnd : nextEnd;
                double newDuration = (double) (newEnd.toEpochMilli() - current.timestamp().toEpochMilli()) / 1000.0;
                current = new Event(current.timestamp(), newDuration, current.data());
            } else {
                union.add(current);
                current = next;
            }
        }
        union.add(current);
        return union;
    }
}
