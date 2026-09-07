package com.selfanalyst.events.query.function;

import com.selfanalyst.events.model.Event;
import com.selfanalyst.events.query.AqlFunction;
import com.selfanalyst.events.query.AqlContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;

public class Categorize implements AqlFunction {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    @SuppressWarnings("unchecked")
    public List<Event> apply(List<Event> input, Map<String, Object> params, AqlContext ctx) {
        Object rulesRaw = params.get("0");
        if (rulesRaw == null) return input;

        Map<String, Map<String, String>> rules;
        if (rulesRaw instanceof String) {
            try {
                rules = MAPPER.readValue((String) rulesRaw,
                        new TypeReference<Map<String, Map<String, String>>>() {});
            } catch (Exception e) {
                return input;
            }
        } else if (rulesRaw instanceof Map) {
            rules = (Map<String, Map<String, String>>) rulesRaw;
        } else {
            return input;
        }

        List<Event> result = new ArrayList<>();
        for (Event e : input) {
            String category = findCategory(e.data(), rules);
            Map<String, Object> newData = new HashMap<>(e.data());
            newData.put("$category", category);
            result.add(new Event(e.id(), e.timestamp(), e.duration(), newData));
        }
        return result;
    }

    private String findCategory(Map<String, Object> data, Map<String, Map<String, String>> rules) {
        for (var entry : rules.entrySet()) {
            String category = entry.getKey();
            Map<String, String> conditions = entry.getValue();
            boolean match = true;
            for (var cond : conditions.entrySet()) {
                Object val = data.get(cond.getKey());
                if (val == null || !String.valueOf(val).equals(cond.getValue())) {
                    match = false;
                    break;
                }
            }
            if (match) return category;
        }
        return "uncategorized";
    }
}
