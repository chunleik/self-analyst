package com.selfanalyst.aw.query.function;

import com.selfanalyst.aw.model.Event;
import com.selfanalyst.aw.query.AqlFunction;
import com.selfanalyst.aw.query.AqlContext;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class FilterKeyvalsRegex implements AqlFunction {
    @Override
    public List<Event> apply(List<Event> input, Map<String, Object> params, AqlContext ctx) {
        String key = (String) params.get("0");
        String regex = (String) params.get("1");
        if (key == null || regex == null || key.isBlank() || regex.isBlank()) {
            return List.of();
        }

        Pattern pattern = Pattern.compile(regex);

        return input.stream()
                .filter(e -> {
                    Object val = e.data().get(key);
                    return val != null && pattern.matcher(String.valueOf(val)).find();
                })
                .toList();
    }
}
