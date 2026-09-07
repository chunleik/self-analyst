package com.selfanalyst.events.query.function;

import com.selfanalyst.events.model.Event;
import com.selfanalyst.events.query.AqlFunction;
import com.selfanalyst.events.query.AqlContext;

import java.util.List;
import java.util.Map;

public class FindBucket implements AqlFunction {
    @Override
    public List<Event> apply(List<Event> input, Map<String, Object> params, AqlContext ctx) {
        String filter = params.getOrDefault("0", "").toString();
        var allBuckets = ctx.bucketStore().listAll();
        List<String> matching = allBuckets.stream()
                .map(b -> b.id())
                .filter(id -> id.contains(filter))
                .toList();
        // Placeholder: returns bucket IDs as data in events for inspection
        return List.of();
    }
}
