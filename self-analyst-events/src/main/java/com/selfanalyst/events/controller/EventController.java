package com.selfanalyst.events.controller;

import com.selfanalyst.events.model.Event;
import com.selfanalyst.events.projection.EventIngestionService;
import com.selfanalyst.events.store.BucketStore;
import com.selfanalyst.events.store.EventStore;
import com.selfanalyst.events.store.ContentEventPolicyViolationException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.http.Context;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class EventController {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final EventStore eventStore;
    private final BucketStore bucketStore;
    private final EventIngestionService ingestionService;

    public EventController(EventStore eventStore, BucketStore bucketStore,
                           EventIngestionService ingestionService) {
        this.eventStore = eventStore;
        this.bucketStore = bucketStore;
        this.ingestionService = ingestionService;
    }

    public void query(Context ctx) {
        try {
            String bucketId = ctx.pathParam("id");
            int limit = 100;
            String limitStr = ctx.queryParam("limit");
            if (limitStr != null && !limitStr.isBlank()) {
                limit = Integer.parseInt(limitStr);
            }
            String start = ctx.queryParam("start");
            String end = ctx.queryParam("end");

            List<Event> events = eventStore.queryEvents(bucketId, limit, start, end);
            List<Map<String, Object>> result = events.stream()
                    .map(EventController::eventToMap)
                    .toList();
            ctx.json(result);
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", "Failed to query events: " + e.getMessage()));
        }
    }

    public void insert(Context ctx) {
        try {
            String bucketId = ctx.pathParam("id");
            var bucket = bucketStore.get(bucketId);
            if (bucket.isEmpty()) {
                ctx.status(404).json(Map.of("error", "Bucket not found: " + bucketId));
                return;
            }

            var bodyNode = MAPPER.readTree(ctx.body());
            List<Map<String, Object>> eventsList = new ArrayList<>();

            if (bodyNode.isArray()) {
                for (var node : bodyNode) {
                    eventsList.add(MAPPER.convertValue(node, new TypeReference<Map<String, Object>>() {}));
                }
            } else {
                eventsList.add(MAPPER.convertValue(bodyNode, new TypeReference<Map<String, Object>>() {}));
            }

            List<Event> parsedEvents = new ArrayList<>();
            List<String> sourceEventIds = new ArrayList<>();
            for (Map<String, Object> eventData : eventsList) {
                Instant timestamp = Instant.parse((String) eventData.get("timestamp"));
                double duration = eventData.containsKey("duration")
                        ? ((Number) eventData.get("duration")).doubleValue()
                        : 0.0;
                @SuppressWarnings("unchecked")
                Map<String, Object> data = eventData.containsKey("data")
                        ? (Map<String, Object>) eventData.get("data")
                        : Map.of();

                parsedEvents.add(new Event(timestamp, duration, data));
                sourceEventIds.add(eventData.get("sourceEventId") instanceof String value
                        ? value : null);
            }

            // Validate the entire batch before the first write so policy failures are atomic.
            for (Event event : parsedEvents) {
                eventStore.validateEvent(bucketId, event);
            }
            List<EventIngestionService.Submission> submissions = new ArrayList<>();
            for (int i = 0; i < parsedEvents.size(); i++) {
                submissions.add(new EventIngestionService.Submission(
                        parsedEvents.get(i), sourceEventIds.get(i)));
            }
            EventIngestionService.Result ingestion = ingestionService.ingest(bucket.get(), submissions);
            bucketStore.updateLastUpdated(bucketId);
            ctx.status(ingestion.projectionPending() ? 202 : 201).json(Map.of(
                    "success", true,
                    "count", eventsList.size(),
                    "rawEventIds", ingestion.rawEvents().stream().map(
                            com.selfanalyst.events.raw.RawEvent::eventId).toList(),
                    "projectionStatus", ingestion.projectionPending() ? "pending" : "projected"));
        } catch (ContentEventPolicyViolationException e) {
            ctx.status(422).json(Map.of(
                    "error", "Content event violates persisted-field policy",
                    "field", e.field()));
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", "Failed to insert events: " + e.getMessage()));
        }
    }

    private static Map<String, Object> eventToMap(Event e) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", e.id());
        map.put("timestamp", e.timestamp().toString());
        map.put("duration", e.duration());
        map.put("data", e.data());
        return map;
    }
}
