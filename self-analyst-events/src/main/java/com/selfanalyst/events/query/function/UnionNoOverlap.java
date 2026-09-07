package com.selfanalyst.events.query.function;

import com.selfanalyst.events.model.Event;
import com.selfanalyst.events.query.AqlFunction;
import com.selfanalyst.events.query.AqlContext;

import java.time.Instant;
import java.util.*;

public class UnionNoOverlap implements AqlFunction {
    @Override
    public List<Event> apply(List<Event> input, Map<String, Object> params, AqlContext ctx) {
        if (input.isEmpty()) return input;

        List<Event> sorted = new ArrayList<>(input);
        sorted.sort(Comparator.comparing(Event::timestamp));

        List<Event> result = new ArrayList<>();
        Event current = sorted.getFirst();

        for (int i = 1; i < sorted.size(); i++) {
            Event next = sorted.get(i);
            Instant curEnd = current.timestamp().plusMillis((long) (current.duration() * 1000));

            if (next.timestamp().isBefore(curEnd)) {
                // Overlap: split the current event, then insert the remainder
                Instant splitPoint = next.timestamp();
                double firstDuration = (double) (splitPoint.toEpochMilli()
                        - current.timestamp().toEpochMilli()) / 1000.0;
                if (firstDuration > 0) {
                    result.add(new Event(current.timestamp(), firstDuration, current.data()));
                }
                double secondDuration = (double) (curEnd.toEpochMilli()
                        - splitPoint.toEpochMilli()) / 1000.0;
                current = new Event(splitPoint, Math.max(secondDuration, 0), current.data());
            } else {
                result.add(current);
                current = next;
            }
        }
        result.add(current);

        // Merge adjacent events with same data
        return mergeAdjacent(result);
    }

    private List<Event> mergeAdjacent(List<Event> events) {
        if (events.size() < 2) return events;
        List<Event> merged = new ArrayList<>();
        Event current = events.getFirst();
        for (int i = 1; i < events.size(); i++) {
            Event next = events.get(i);
            Instant curEnd = current.timestamp().plusMillis((long) (current.duration() * 1000));
            if (curEnd.equals(next.timestamp()) && current.data().equals(next.data())) {
                double newDuration = current.duration() + next.duration();
                current = new Event(current.timestamp(), newDuration, current.data());
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);
        return merged;
    }
}
