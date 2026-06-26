package com.selfanalyst.aw.query.function;

import com.selfanalyst.aw.model.Event;
import com.selfanalyst.aw.query.AqlFunction;
import com.selfanalyst.aw.query.AqlContext;

import java.util.*;

public class Tag implements AqlFunction {
    @Override
    public List<Event> apply(List<Event> input, Map<String, Object> params, AqlContext ctx) {
        String tagsStr = (String) params.get("0");
        if (tagsStr == null || tagsStr.isBlank()) return input;

        Map<String, String> tags = new LinkedHashMap<>();
        String[] pairs = tagsStr.split(",");
        for (String pair : pairs) {
            String[] kv = pair.split(":", 2);
            if (kv.length == 2) {
                tags.put(kv[0].trim(), kv[1].trim());
            }
        }

        if (tags.isEmpty()) return input;

        List<Event> result = new ArrayList<>();
        for (Event e : input) {
            Map<String, Object> newData = new HashMap<>(e.data());
            newData.putAll(tags);
            result.add(new Event(e.id(), e.timestamp(), e.duration(), newData));
        }
        return result;
    }
}
