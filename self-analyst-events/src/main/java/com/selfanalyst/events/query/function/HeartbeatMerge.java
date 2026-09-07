package com.selfanalyst.events.query.function;

import com.selfanalyst.events.model.Event;
import com.selfanalyst.events.query.AqlFunction;
import com.selfanalyst.events.query.AqlContext;

import java.time.Instant;
import java.util.*;

public class HeartbeatMerge implements AqlFunction {
    @Override
    public List<Event> apply(List<Event> input, Map<String, Object> params, AqlContext ctx) {
        if (input.isEmpty()) return input;

        double pulseTime;
        Object pulseRaw = params.get("0");
        if (pulseRaw instanceof Number) {
            pulseTime = ((Number) pulseRaw).doubleValue();
        } else if (pulseRaw instanceof String) {
            pulseTime = Double.parseDouble((String) pulseRaw);
        } else {
            pulseTime = 300.0; // default 5 minutes
        }

        List<Event> sorted = new ArrayList<>(input);
        sorted.sort(Comparator.comparing(Event::timestamp));

        List<Event> merged = new ArrayList<>();
        Event current = sorted.getFirst();

        for (int i = 1; i < sorted.size(); i++) {
            Event next = sorted.get(i);
            Instant curEnd = current.timestamp().plusMillis((long) (current.duration() * 1000));
            long gapMillis = next.timestamp().toEpochMilli() - curEnd.toEpochMilli();
            double gapSeconds = gapMillis / 1000.0;

            if (gapSeconds >= 0 && gapSeconds <= pulseTime && current.data().equals(next.data())) {
                double newDuration = (double) (next.timestamp().toEpochMilli()
                        + (long) (next.duration() * 1000)
                        - current.timestamp().toEpochMilli()) / 1000.0;
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
