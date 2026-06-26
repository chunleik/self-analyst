package com.selfanalyst.aw.query.function;

import com.selfanalyst.aw.model.Event;
import com.selfanalyst.aw.query.AqlFunction;
import com.selfanalyst.aw.query.AqlContext;

import java.time.Instant;
import java.util.*;

public class HeartbeatReduce implements AqlFunction {
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
            pulseTime = 300.0;
        }

        List<Event> sorted = new ArrayList<>(input);
        sorted.sort(Comparator.comparing(Event::timestamp));

        List<Event> result = new ArrayList<>();
        Event current = sorted.getFirst();

        for (int i = 1; i < sorted.size(); i++) {
            Event next = sorted.get(i);
            Instant curEnd = current.timestamp().plusMillis((long) (current.duration() * 1000));
            long gapMillis = next.timestamp().toEpochMilli() - curEnd.toEpochMilli();
            double gapSeconds = gapMillis / 1000.0;

            if (gapSeconds >= 0 && gapSeconds <= pulseTime && current.data().equals(next.data())) {
                // Merge: keep current but extend duration to next end
                double newDuration = (double) (next.timestamp().toEpochMilli()
                        + (long) (next.duration() * 1000)
                        - current.timestamp().toEpochMilli()) / 1000.0;
                current = new Event(current.timestamp(), newDuration, Map.copyOf(current.data()));
            } else {
                result.add(current);
                current = next;
            }
        }
        result.add(current);
        return result;
    }
}
