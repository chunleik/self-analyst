package com.selfanalyst.events.query.function;

import com.selfanalyst.events.model.Event;
import com.selfanalyst.events.query.AqlFunction;
import com.selfanalyst.events.query.AqlContext;

import java.net.URI;
import java.util.*;

public class SplitUrlEvents implements AqlFunction {
    @Override
    public List<Event> apply(List<Event> input, Map<String, Object> params, AqlContext ctx) {
        List<Event> result = new ArrayList<>();
        for (Event e : input) {
            Object urlObj = e.data().get("url");
            if (urlObj == null) {
                result.add(e);
                continue;
            }
            try {
                URI uri = URI.create(String.valueOf(urlObj));
                Map<String, Object> newData = new HashMap<>(e.data());
                newData.put("protocol", uri.getScheme() != null ? uri.getScheme() : "unknown");
                newData.put("domain", uri.getHost() != null ? uri.getHost() : "unknown");
                newData.put("path", uri.getPath() != null ? uri.getPath() : "/");
                result.add(new Event(e.id(), e.timestamp(), e.duration(), newData));
            } catch (Exception ex) {
                result.add(e);
            }
        }
        return result;
    }
}
