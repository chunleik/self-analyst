package com.selfanalyst.aw.query.function;

import com.selfanalyst.aw.model.Event;
import com.selfanalyst.aw.query.AqlFunction;
import com.selfanalyst.aw.query.AqlContext;

import java.util.List;
import java.util.Map;

public class LimitEvents implements AqlFunction {
    @Override
    public List<Event> apply(List<Event> input, Map<String, Object> params, AqlContext ctx) {
        int limit;
        Object limitRaw = params.get("0");
        if (limitRaw instanceof Number) {
            limit = ((Number) limitRaw).intValue();
        } else if (limitRaw instanceof String) {
            limit = Integer.parseInt((String) limitRaw);
        } else {
            return input;
        }
        if (limit < 0) return List.of();
        return input.stream().limit(limit).toList();
    }
}
