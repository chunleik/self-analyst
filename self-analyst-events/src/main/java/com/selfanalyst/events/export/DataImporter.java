package com.selfanalyst.events.export;

import com.selfanalyst.events.model.Bucket;
import com.selfanalyst.events.model.Event;
import com.selfanalyst.events.store.BucketStore;
import com.selfanalyst.events.store.EventStore;
import com.selfanalyst.events.store.ContentEventPolicy;
import com.selfanalyst.events.projection.RawEventProjector;
import com.selfanalyst.events.raw.CanonicalJson;
import com.selfanalyst.events.raw.RawEvent;
import com.selfanalyst.events.raw.RawEventAppender;
import com.selfanalyst.events.raw.RawEventIdGenerator;
import com.selfanalyst.events.raw.RawEventSource;
import com.selfanalyst.events.raw.RawIngestKind;

import java.time.Instant;
import java.util.*;

public class DataImporter {

    private final BucketStore bucketStore;
    private final EventStore eventStore;
    private final RawEventAppender rawAppender;
    private final RawEventProjector projector;
    private final RawEventIdGenerator ids = new RawEventIdGenerator();

    public DataImporter(BucketStore bucketStore, EventStore eventStore,
                        RawEventAppender rawAppender, RawEventProjector projector) {
        this.bucketStore = bucketStore;
        this.eventStore = eventStore;
        this.rawAppender = rawAppender;
        this.projector = projector;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> importData(Map<String, Object> data) {
        int bucketsImported = 0;
        int eventsImported = 0;
        boolean projectionPending = false;

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

        Map<String, Bucket> resolvedBuckets = new HashMap<>();
        List<Bucket> newBuckets = new ArrayList<>();
        if (buckets != null) {
            for (Map<String, Object> bucketMap : buckets) {
                String id = (String) bucketMap.get("id");
                Bucket bucket = bucketStore.get(id).orElseGet(() -> Bucket.create(
                        id, (String) bucketMap.getOrDefault("name", id),
                        (String) bucketMap.getOrDefault("type", "unknown"),
                        (String) bucketMap.getOrDefault("client", "unknown"),
                        (String) bucketMap.getOrDefault("hostname", "unknown")));
                resolvedBuckets.put(id, bucket);
                if (bucketStore.get(id).isEmpty()) newBuckets.add(bucket);
            }
        }

        String importSessionId = data.get("importSessionId") instanceof String explicit
                && !explicit.isBlank() ? explicit : derivedSessionId(data);
        Instant receivedAt = Instant.now();
        List<RawEvent> rawEvents = new ArrayList<>();
        if (eventsMap != null) {
            for (String bucketId : new TreeSet<>(eventsMap.keySet())) {
                List<Map<String, Object>> events = eventsMap.get(bucketId);
                if (events == null) continue;
                Bucket bucket = bucketStore.get(bucketId).orElse(resolvedBuckets.get(bucketId));
                for (int ordinal = 0; ordinal < events.size(); ordinal++) {
                    Map<String, Object> eventMap = events.get(ordinal);
                    Instant timestamp = Instant.parse((String) eventMap.get("timestamp"));
                    double duration = eventMap.containsKey("duration")
                            ? ((Number) eventMap.get("duration")).doubleValue() : 0.0;
                    Map<String, Object> eventData = eventMap.containsKey("data")
                            ? (Map<String, Object>) eventMap.get("data") : Map.of();
                    int schemaVersion = ContentEventPolicy.isContentBucket(
                            bucketId, bucket.client()) ? 2 : 1;
                    rawEvents.add(RawEvent.create(ids, null, bucketId, RawEventSource.IMPORT,
                            schemaVersion, RawIngestKind.IMPORT, timestamp, receivedAt,
                            duration, eventData, importSessionId, ordinal));
                }
            }
        }
        List<RawEvent> storedRawEvents = rawAppender.appendBatch(rawEvents);

        // Raw commit succeeded; only now create projection bucket metadata.
        if (buckets != null) {
            for (Bucket bucket : newBuckets) {
                bucketStore.create(bucket);
                bucketsImported++;
            }
        }

        for (RawEvent rawEvent : storedRawEvents) {
            try {
                projector.project(rawEvent);
            } catch (RuntimeException failure) {
                projectionPending = true;
            }
            eventsImported++;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("buckets_imported", bucketsImported);
        result.put("events_imported", eventsImported);
        result.put("import_session_id", importSessionId);
        result.put("projection_status", projectionPending ? "pending" : "projected");
        return result;
    }

    private static String derivedSessionId(Map<String, Object> data) {
        LinkedHashMap<String, Object> identity = new LinkedHashMap<>(data);
        identity.remove("importSessionId");
        return "import-v1-" + CanonicalJson.encode(identity).sha256();
    }
}
