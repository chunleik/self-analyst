package com.selfanalyst.aw.query.function;

import com.selfanalyst.aw.model.Event;
import com.selfanalyst.aw.query.AqlFunction;
import com.selfanalyst.aw.query.AqlContext;

import java.time.Instant;
import java.util.*;

public class ChunkEventsByKey implements AqlFunction {
    @Override
    public List<Event> apply(List<Event> input, Map<String, Object> params, AqlContext ctx) {
        String key = (String) params.get("0");
        if (key == null || key.isBlank() || input.isEmpty()) return input;

        Map<Object, List<Event>> groups = new LinkedHashMap<>();
        for (Event e : input) {
            Object val = e.data().get(key);
            if (val != null) {
                groups.computeIfAbsent(val, k -> new ArrayList<>()).add(e);
            }
        }

        List<Event> result = new ArrayList<>();
        for (Map.Entry<Object, List<Event>> entry : groups.entrySet()) {
            List<Event> group = entry.getValue();
            double totalDuration = group.stream().mapToDouble(Event::duration).sum();
            Instant firstTimestamp = group.stream()
                    .map(Event::timestamp)
                    .min(Instant::compareTo)
                    .orElse(Instant.now());
            Map<String, Object> data = new HashMap<>(group.getFirst().data());
            result.add(new Event(firstTimestamp, totalDuration, data));
        }
        return result;
    }
}
