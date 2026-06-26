package com.selfanalyst.aw.export;

import com.selfanalyst.aw.model.Bucket;
import com.selfanalyst.aw.model.Event;
import com.selfanalyst.aw.store.BucketStore;
import com.selfanalyst.aw.store.EventStore;

import java.util.*;

public class DataExporter {

    private final BucketStore bucketStore;
    private final EventStore eventStore;

    public DataExporter(BucketStore bucketStore, EventStore eventStore) {
        this.bucketStore = bucketStore;
        this.eventStore = eventStore;
    }

    public Map<String, Object> exportAll() {
        List<Bucket> buckets = bucketStore.listAll();
        List<Map<String, Object>> bucketList = new ArrayList<>();
        Map<String, List<Map<String, Object>>> eventsMap = new LinkedHashMap<>();

        for (Bucket bucket : buckets) {
            bucketList.add(bucketToMap(bucket));
            List<Event> events = eventStore.queryAllEvents(bucket.id());
            eventsMap.put(bucket.id(), events.stream().map(DataExporter::eventToMap).toList());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("buckets", bucketList);
        result.put("events", eventsMap);
        return result;
    }

    public Map<String, Object> exportBucket(String bucketId) {
        var bucketOpt = bucketStore.get(bucketId);
        if (bucketOpt.isEmpty()) {
            throw new NoSuchElementException("Bucket not found: " + bucketId);
        }

        Bucket bucket = bucketOpt.get();
        List<Map<String, Object>> bucketList = List.of(bucketToMap(bucket));
        List<Map<String, Object>> events = eventStore.queryAllEvents(bucketId).stream()
                .map(DataExporter::eventToMap)
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("buckets", bucketList);
        result.put("events", Map.of(bucketId, events));
        return result;
    }

    private static Map<String, Object> bucketToMap(Bucket bucket) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", bucket.id());
        map.put("name", bucket.name());
        map.put("type", bucket.type());
        map.put("client", bucket.client());
        map.put("hostname", bucket.hostname());
        map.put("created", bucket.created().toString());
        map.put("last_updated", bucket.lastUpdated().toString());
        return map;
    }

    private static Map<String, Object> eventToMap(Event event) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", event.id());
        map.put("timestamp", event.timestamp().toString());
        map.put("duration", event.duration());
        map.put("data", event.data());
        return map;
    }
}
