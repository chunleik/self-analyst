package com.selfanalyst.aw.query.function;

import com.selfanalyst.aw.model.Event;
import com.selfanalyst.aw.query.AqlFunction;
import com.selfanalyst.aw.query.AqlContext;

import java.time.Instant;
import java.util.*;

public class Flood implements AqlFunction {
    @Override
    public List<Event> apply(List<Event> input, Map<String, Object> params, AqlContext ctx) {
        if (input.isEmpty()) return input;

        List<Event> sorted = new ArrayList<>(input);
        sorted.sort(Comparator.comparing(Event::timestamp));

        List<Event> result = new ArrayList<>();
        for (int i = 0; i < sorted.size() - 1; i++) {
            Event current = sorted.get(i);
            Event next = sorted.get(i + 1);
            Instant curEnd = current.timestamp().plusMillis((long) (current.duration() * 1000));
            Instant nextStart = next.timestamp();

            if (curEnd.isBefore(nextStart)) {
                // Extend current event to fill gap
                double newDuration = (double) (nextStart.toEpochMilli()
                        - current.timestamp().toEpochMilli()) / 1000.0;
                result.add(new Event(current.timestamp(), newDuration, current.data()));
            } else {
                result.add(current);
            }
        }
        result.add(sorted.getLast());
        return result;
    }
}
