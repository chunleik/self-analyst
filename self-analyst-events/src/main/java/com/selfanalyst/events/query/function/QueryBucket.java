package com.selfanalyst.events.query.function;

import com.selfanalyst.events.model.Event;
import com.selfanalyst.events.query.AqlFunction;
import com.selfanalyst.events.query.AqlContext;

import java.util.List;
import java.util.Map;

public class QueryBucket implements AqlFunction {
    @Override
    public List<Event> apply(List<Event> input, Map<String, Object> params, AqlContext ctx) {
        String bucketId = (String) params.get("0");
        if (bucketId == null || bucketId.isBlank()) {
            return List.of();
        }
        return ctx.eventStore().queryAllEvents(bucketId);
    }
}
