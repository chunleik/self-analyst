package com.selfanalyst.aw.query.function;

import com.selfanalyst.aw.model.Event;
import com.selfanalyst.aw.query.AqlFunction;
import com.selfanalyst.aw.query.AqlContext;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public class SumDurations implements AqlFunction {
    @Override
    public List<Event> apply(List<Event> input, Map<String, Object> params, AqlContext ctx) {
        if (input.isEmpty()) return List.of();

        double total = input.stream().mapToDouble(Event::duration).sum();
        Instant earliest = input.stream()
                .map(Event::timestamp)
                .min(Instant::compareTo)
                .orElse(Instant.now());

        Event summary = new Event(earliest, total, Map.of());
        return List.of(summary);
    }
}
