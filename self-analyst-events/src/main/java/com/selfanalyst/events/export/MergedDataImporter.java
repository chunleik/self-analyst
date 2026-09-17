package com.selfanalyst.events.export;

import com.selfanalyst.events.model.Bucket;
import com.selfanalyst.events.model.Event;
import com.selfanalyst.events.raw.CanonicalJson;
import com.selfanalyst.events.store.MergedEventStore;
import java.time.Instant;
import java.util.*;

/** 先解析整份输入，再在一个事务中提交 bucket 和事件。 */
public final class MergedDataImporter {
    private final MergedEventStore store;
    public MergedDataImporter(MergedEventStore store) { this.store = store; }

    @SuppressWarnings("unchecked")
    public Map<String, Object> importData(Map<String, Object> input) {
        String session = input.get("importSessionId") instanceof String id && !id.isBlank()
                ? id : "import-v1-" + CanonicalJson.encode(input).sha256();
        if (session.length() > 256) throw new IllegalArgumentException("Import identity too long");
        List<Bucket> buckets = new ArrayList<>();
        for (var bucket : (List<Map<String, Object>>) input.getOrDefault("buckets", List.of())) {
            String id = (String) bucket.get("id");
            buckets.add(Bucket.create(id, (String) bucket.getOrDefault("name", id),
                    (String) bucket.getOrDefault("type", "unknown"),
                    (String) bucket.getOrDefault("client", "unknown"),
                    (String) bucket.getOrDefault("hostname", "unknown")));
        }
        Map<String, List<MergedEventStore.Submission>> events = new LinkedHashMap<>();
        int count = 0;
        for (var entry : ((Map<String, List<Map<String, Object>>>) input.getOrDefault("events", Map.of())).entrySet()) {
            var list = new ArrayList<MergedEventStore.Submission>();
            if (entry.getValue() != null) for (var row : entry.getValue()) {
                Event event = new Event(Instant.parse((String) row.get("timestamp")),
                        ((Number) row.getOrDefault("duration", 0)).doubleValue(),
                        (Map<String, Object>) row.getOrDefault("data", Map.of()));
                list.add(new MergedEventStore.Submission(event, "import:" + session + ":" + list.size()));
                if (++count > 100_000) throw new IllegalArgumentException("Import exceeds event limit");
            }
            events.put(entry.getKey(), list);
        }
        store.importBatch(buckets, events);
        return Map.of("success", true, "buckets_imported", buckets.size(), "events_imported", count,
                "import_session_id", session, "storageMode", "merged");
    }
}
