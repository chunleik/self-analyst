package com.selfanalyst.aw.export;

import com.selfanalyst.aw.model.Bucket;
import com.selfanalyst.aw.model.Event;
import com.selfanalyst.aw.store.BucketStore;
import com.selfanalyst.aw.store.EventStore;
import com.selfanalyst.aw.store.ContentEventPolicy;

import java.time.Instant;
import java.util.*;

public class DataImporter {

    private final BucketStore bucketStore;
    private final EventStore eventStore;

    public DataImporter(BucketStore bucketStore, EventStore eventStore) {
        this.bucketStore = bucketStore;
        this.eventStore = eventStore;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> importData(Map<String, Object> data) {
        int bucketsImported = 0;
        int eventsImported = 0;

        List<Map<String, Object>> buckets = (List<Map<String, Object>>) data.get("buckets");
        Map<String, String> importedClients = new HashMap<>();
        if (buckets != null) {
            for (Map<String, Object> bucketMap : buckets) {
                String id = (String) bucketMap.get("id");
                BucketStore.validateId(id);
                importedClients.put(id,
                        (String) bucketMap.getOrDefault("client", "unknown"));
            }
        }

        // Preflight all imported content events before creating buckets or writing rows.
        Map<String, List<Map<String, Object>>> eventsMap =
                (Map<String, List<Map<String, Object>>>) data.get("events");
        if (eventsMap != null) {
            for (Map.Entry<String, List<Map<String, Object>>> entry : eventsMap.entrySet()) {
                String bucketId = entry.getKey();
                BucketStore.validateId(bucketId);
                var existingBucket = bucketStore.get(bucketId);
                String client = existingBucket.map(Bucket::client)
                        .orElse(importedClients.get(bucketId));
                if (existingBucket.isEmpty() && !importedClients.containsKey(bucketId)) {
                    throw new IllegalArgumentException(
                            "Imported events reference an undefined bucket: " + bucketId);
                }
                if (entry.getValue() == null) continue;
                for (Map<String, Object> eventMap : entry.getValue()) {
                    Map<String, Object> eventData = eventMap.containsKey("data")
                            ? (Map<String, Object>) eventMap.get("data")
                            : Map.of();
                    ContentEventPolicy.validate(bucketId, client, eventData);
                }
            }
        }

        // Import buckets
        if (buckets != null) {
            for (Map<String, Object> bucketMap : buckets) {
                String id = (String) bucketMap.get("id");
                if (bucketStore.get(id).isPresent()) {
                    continue; // Skip existing buckets
                }

                String name = (String) bucketMap.getOrDefault("name", id);
                String type = (String) bucketMap.getOrDefault("type", "unknown");
                String client = (String) bucketMap.getOrDefault("client", "unknown");
                String hostname = (String) bucketMap.getOrDefault("hostname", "unknown");

                Bucket bucket = Bucket.create(id, name, type, client, hostname);
                bucketStore.create(bucket);
                bucketsImported++;
            }
        }

        // Import events
        if (eventsMap != null) {
            for (Map.Entry<String, List<Map<String, Object>>> entry : eventsMap.entrySet()) {
                String bucketId = entry.getKey();
                List<Map<String, Object>> events = entry.getValue();
                if (events == null) continue;

                for (Map<String, Object> eventMap : events) {
                    Instant timestamp = Instant.parse((String) eventMap.get("timestamp"));
                    double duration = eventMap.containsKey("duration")
                            ? ((Number) eventMap.get("duration")).doubleValue()
                            : 0.0;
                    Map<String, Object> eventData = eventMap.containsKey("data")
                            ? (Map<String, Object>) eventMap.get("data")
                            : Map.of();

                    Event event = new Event(timestamp, duration, eventData);
                    eventStore.insertEvent(bucketId, event);
                    eventsImported++;
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("buckets_imported", bucketsImported);
        result.put("events_imported", eventsImported);
        return result;
    }
}
