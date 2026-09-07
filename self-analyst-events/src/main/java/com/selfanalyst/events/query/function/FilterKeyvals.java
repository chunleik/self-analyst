package com.selfanalyst.events.query.function;

import com.selfanalyst.events.model.Event;
import com.selfanalyst.events.query.AqlFunction;
import com.selfanalyst.events.query.AqlContext;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class FilterKeyvals implements AqlFunction {
    @Override
    @SuppressWarnings("unchecked")
    public List<Event> apply(List<Event> input, Map<String, Object> params, AqlContext ctx) {
        String key = (String) params.get("0");
        if (key == null || key.isBlank()) return List.of();

        Object valuesRaw = params.get("1");
        Set<String> values;
        if (valuesRaw instanceof List) {
            values = ((List<String>) valuesRaw).stream()
                    .map(String::valueOf)
                    .collect(Collectors.toSet());
        } else if (valuesRaw instanceof String) {
            values = Set.of((String) valuesRaw);
        } else {
            values = Set.of();
        }

        if (values.isEmpty()) return List.of();

        return input.stream()
                .filter(e -> {
                    Object val = e.data().get(key);
                    return val != null && values.contains(String.valueOf(val));
                })
                .toList();
    }
}
