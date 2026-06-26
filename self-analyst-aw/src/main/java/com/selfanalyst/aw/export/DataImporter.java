package com.selfanalyst.aw.export;

import com.selfanalyst.aw.model.Bucket;
import com.selfanalyst.aw.model.Event;
import com.selfanalyst.aw.store.BucketStore;
import com.selfanalyst.aw.store.EventStore;

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

        // Import buckets
        List<Map<String, Object>> buckets = (List<Map<String, Object>>) data.get("buckets");
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
        Map<String, List<Map<String, Object>>> eventsMap = (Map<String, List<Map<String, Object>>>) data.get("events");
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
