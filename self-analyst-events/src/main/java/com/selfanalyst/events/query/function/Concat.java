package com.selfanalyst.events.query.function;

import com.selfanalyst.events.model.Event;
import com.selfanalyst.events.query.AqlFunction;
import com.selfanalyst.events.query.AqlContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class Concat implements AqlFunction {
    @Override
    @SuppressWarnings("unchecked")
    public List<Event> apply(List<Event> input, Map<String, Object> params, AqlContext ctx) {
        Object otherRaw = params.get("0");
        List<Event> other;
        if (otherRaw instanceof List) {
            other = (List<Event>) otherRaw;
        } else {
            other = List.of();
        }

        List<Event> result = new ArrayList<>();
        result.addAll(input);
        result.addAll(other);
        return result;
    }
}
