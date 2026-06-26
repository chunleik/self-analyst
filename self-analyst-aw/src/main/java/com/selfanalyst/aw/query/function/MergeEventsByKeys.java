package com.selfanalyst.aw.query.function;

import com.selfanalyst.aw.model.Event;
import com.selfanalyst.aw.query.AqlFunction;
import com.selfanalyst.aw.query.AqlContext;

import java.util.*;

public class MergeEventsByKeys implements AqlFunction {
    @Override
    public List<Event> apply(List<Event> input, Map<String, Object> params, AqlContext ctx) {
        Object keysObj = params.get("0");
        String keysStr;
        if (keysObj instanceof List<?> list) {
            keysStr = list.stream().map(Object::toString).reduce((a, b) -> a + "," + b).orElse("");
        } else {
            keysStr = (String) keysObj;
        }
        if (keysStr == null || keysStr.isBlank() || input.isEmpty()) return input;

        String[] keys = keysStr.split(",");
        for (int i = 0; i < keys.length; i++) {
            keys[i] = keys[i].trim();
        }

        List<Event> merged = new ArrayList<>();
        Event current = input.getFirst();

        for (int i = 1; i < input.size(); i++) {
            Event next = input.get(i);
            if (sameKeyValues(current.data(), next.data(), keys)) {
                double newDuration = calculateDuration(current, next);
                current = new Event(current.timestamp(), newDuration, current.data());
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);
        return merged;
    }

    private boolean sameKeyValues(Map<String, Object> d1, Map<String, Object> d2, String[] keys) {
        for (String key : keys) {
            Object v1 = d1.get(key);
            Object v2 = d2.get(key);
            if (!Objects.equals(v1, v2)) return false;
        }
        return true;
    }

    private double calculateDuration(Event e1, Event e2) {
        long start = e1.timestamp().toEpochMilli();
        long end1 = start + (long) (e1.duration() * 1000);
        long end2 = e2.timestamp().toEpochMilli() + (long) (e2.duration() * 1000);
        return (Math.max(end1, end2) - start) / 1000.0;
    }
}
