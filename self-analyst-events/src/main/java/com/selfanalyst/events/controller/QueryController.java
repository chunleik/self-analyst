package com.selfanalyst.events.controller;

import com.selfanalyst.events.query.AqlContext;
import com.selfanalyst.events.query.AqlInterpreter;
import com.selfanalyst.events.store.BucketStore;
import com.selfanalyst.events.store.EventStore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.http.Context;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class QueryController {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final EventStore eventStore;
    private final BucketStore bucketStore;

    public QueryController(EventStore eventStore, BucketStore bucketStore) {
        this.eventStore = eventStore;
        this.bucketStore = bucketStore;
    }

    public void handle(Context ctx) {
        try {
            Map<String, Object> body = MAPPER.readValue(ctx.body(), new TypeReference<Map<String, Object>>() {});

            // Extract timeperiods
            @SuppressWarnings("unchecked")
            List<String> timeperiods = (List<String>) body.get("timeperiods");
            if (timeperiods == null || timeperiods.isEmpty()) {
                ctx.status(400).json(Map.of("error", "Missing or empty timeperiods"));
                return;
            }

            // First timeperiod is formatted as "start/end"
            String timeperiod = timeperiods.get(0);
            String[] parts = timeperiod.split("/", 2);
            if (parts.length != 2) {
                ctx.status(400).json(Map.of("error", "Invalid timeperiod format, expected 'start/end'"));
                return;
            }
            String startTime = parts[0];
            String endTime = parts[1];

            // Extract and join query lines
            @SuppressWarnings("unchecked")
            List<String> queryLines = (List<String>) body.get("query");
            if (queryLines == null || queryLines.isEmpty()) {
                ctx.status(400).json(Map.of("error", "Missing or empty query"));
                return;
            }
            String query = queryLines.stream().collect(Collectors.joining(" "));

            // Execute AQL
            AqlContext aqlContext = new AqlContext(eventStore, bucketStore, startTime, endTime);
            AqlInterpreter interpreter = new AqlInterpreter(aqlContext);
            Map<String, Object> result = interpreter.execute(query);

            ctx.json(result);
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", "Failed to execute query: " + e.getMessage()));
        }
    }
}
