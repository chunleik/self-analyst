package com.selfanalyst.aw.controller;

import com.selfanalyst.aw.model.Bucket;
import com.selfanalyst.aw.model.BucketMetadata;
import com.selfanalyst.aw.store.BucketStore;
import com.selfanalyst.aw.store.EventStore;
import com.selfanalyst.aw.store.ContentEventPolicy;
import com.selfanalyst.aw.store.ContentEventPolicyViolationException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.http.Context;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class BucketController {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final BucketStore bucketStore;
    private final EventStore eventStore;

    public BucketController(BucketStore bucketStore, EventStore eventStore) {
        this.bucketStore = bucketStore;
        this.eventStore = eventStore;
    }

    /** Bucket ID prefixes hidden from the AW web UI timeline (too dense to read). */
    private static final List<String> HIDDEN_PREFIXES = List.of(
            ContentEventPolicy.CONTENT_BUCKET_PREFIX,
            ContentEventPolicy.LEGACY_CONTENT_BUCKET_PREFIX);

    public void list(Context ctx) {
        try {
            List<BucketMetadata> buckets = bucketStore.listAllWithCounts(eventStore);
            boolean includeHidden = Boolean.parseBoolean(ctx.queryParam("include_hidden"));
            // Return dict keyed by bucket ID — matches Python AW API format expected by aw-webui
            Map<String, Object> result = new LinkedHashMap<>();
            for (BucketMetadata b : buckets) {
                if (includeHidden
                        || HIDDEN_PREFIXES.stream().noneMatch(p -> b.id().startsWith(p))) {
                    result.put(b.id(), b.toMap());
                }
            }
            ctx.json(result);
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", "Failed to list buckets: " + e.getMessage()));
        }
    }

    public void create(Context ctx) {
        try {
            String id = ctx.pathParam("id");

            // Return 304 if bucket already exists
            if (bucketStore.get(id).isPresent()) {
                ctx.status(304);
                return;
            }

            Map<String, Object> body = MAPPER.readValue(ctx.body(), new TypeReference<Map<String, Object>>() {});

            String name = (String) body.getOrDefault("name", id);
            String type = (String) body.getOrDefault("type", "unknown");
            String client = (String) body.getOrDefault("client", "unknown");
            String hostname = (String) body.getOrDefault("hostname", "unknown");

            // Older servers allowed orphan events before bucket creation. A content bucket
            // must not adopt such rows unless every one already satisfies the v2 policy.
            if (ContentEventPolicy.isContentBucket(id, client)) {
                for (var event : eventStore.queryAllEvents(id)) {
                    ContentEventPolicy.validate(id, client, event.data());
                }
            }

            Bucket bucket = Bucket.create(id, name, type, client, hostname);
            bucketStore.create(bucket);

            BucketMetadata meta = BucketMetadata.fromBucket(bucket, 0);
            ctx.json(meta.toMap());
        } catch (ContentEventPolicyViolationException e) {
            ctx.status(422).json(Map.of(
                    "error", "Content bucket contains events that violate persisted-field policy",
                    "field", e.field()));
        } catch (IllegalArgumentException e) {
            ctx.status(400).json(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", "Failed to create bucket: " + e.getMessage()));
        }
    }

    public void get(Context ctx) {
        try {
            String id = ctx.pathParam("id");
            var bucketOpt = bucketStore.get(id);
            if (bucketOpt.isEmpty()) {
                ctx.status(404).json(Map.of("error", "Bucket not found: " + id));
                return;
            }
            Bucket bucket = bucketOpt.get();
            int count = eventStore.countByBucket(id);
            BucketMetadata meta = BucketMetadata.fromBucket(bucket, count);
            ctx.json(meta.toMap());
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", "Failed to get bucket: " + e.getMessage()));
        }
    }

    public void delete(Context ctx) {
        try {
            String id = ctx.pathParam("id");
            if (bucketStore.get(id).isEmpty()) {
                ctx.status(404).json(Map.of("error", "Bucket not found: " + id));
                return;
            }
            eventStore.deleteByBucket(id);
            bucketStore.delete(id);
            ctx.json(Map.of("success", true));
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", "Failed to delete bucket: " + e.getMessage()));
        }
    }
}
