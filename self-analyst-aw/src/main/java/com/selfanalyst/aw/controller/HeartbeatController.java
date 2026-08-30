package com.selfanalyst.aw.controller;

import com.selfanalyst.aw.model.Event;
import com.selfanalyst.aw.store.BucketStore;
import com.selfanalyst.aw.store.EventStore;
import com.selfanalyst.aw.store.ContentEventPolicyViolationException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.http.Context;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public class HeartbeatController {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final EventStore eventStore;
    private final BucketStore bucketStore;

    public HeartbeatController(EventStore eventStore, BucketStore bucketStore) {
        this.eventStore = eventStore;
        this.bucketStore = bucketStore;
    }

    public void handle(Context ctx) {
        try {
            String bucketId = ctx.pathParam("id");
            if (bucketStore.get(bucketId).isEmpty()) {
                ctx.status(404).json(Map.of("error", "Bucket not found: " + bucketId));
                return;
            }
            Map<String, Object> body = MAPPER.readValue(ctx.body(), new TypeReference<Map<String, Object>>() {});

            Instant timestamp = Instant.parse((String) body.get("timestamp"));
            double duration = body.containsKey("duration")
                    ? ((Number) body.get("duration")).doubleValue()
                    : 0.0;
            @SuppressWarnings("unchecked")
            Map<String, Object> data = body.containsKey("data")
                    ? (Map<String, Object>) body.get("data")
                    : Map.of();

            Event event = new Event(timestamp, duration, data);
            Event result = eventStore.insertHeartbeat(bucketId, event);
            bucketStore.updateLastUpdated(bucketId);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("id", result.id());
            response.put("timestamp", result.timestamp().toString());
            response.put("duration", result.duration());
            response.put("data", result.data());
            ctx.json(response);
        } catch (ContentEventPolicyViolationException e) {
            ctx.status(422).json(Map.of(
                    "error", "Content event violates persisted-field policy",
                    "field", e.field()));
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", "Failed to process heartbeat: " + e.getMessage()));
        }
    }
}
