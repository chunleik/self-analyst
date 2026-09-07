package com.selfanalyst.events.controller;

import com.selfanalyst.events.model.Event;
import com.selfanalyst.events.projection.HeartbeatIngestionService;
import com.selfanalyst.events.raw.ProjectionStatus;
import com.selfanalyst.events.store.BucketStore;
import com.selfanalyst.events.store.EventStore;
import com.selfanalyst.events.store.ContentEventPolicyViolationException;
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
    private final HeartbeatIngestionService ingestionService;

    public HeartbeatController(EventStore eventStore, BucketStore bucketStore,
                               HeartbeatIngestionService ingestionService) {
        this.eventStore = eventStore;
        this.bucketStore = bucketStore;
        this.ingestionService = ingestionService;
    }

    public void handle(Context ctx) {
        try {
            String bucketId = ctx.pathParam("id");
            var bucket = bucketStore.get(bucketId);
            if (bucket.isEmpty()) {
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
            eventStore.validateEvent(bucketId, event);
            String sourceEventId = body.get("sourceEventId") instanceof String value ? value : null;
            HeartbeatIngestionService.Result ingestion =
                    ingestionService.ingest(bucket.get(), event, sourceEventId);
            if (ingestion.projectionStatus() == ProjectionStatus.PENDING) {
                ctx.status(202).json(Map.of(
                        "rawEventId", ingestion.rawEvent().eventId(),
                        "projectionStatus", "pending"));
                return;
            }
            Event result = eventStore.findById(bucketId, ingestion.projectionEventId())
                    .orElseThrow(() -> new IllegalStateException("投影事件不存在"));
            bucketStore.updateLastUpdated(bucketId);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("id", result.id());
            response.put("timestamp", result.timestamp().toString());
            response.put("duration", result.duration());
            response.put("data", result.data());
            response.put("rawEventId", ingestion.rawEvent().eventId());
            response.put("projectionStatus", "projected");
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
