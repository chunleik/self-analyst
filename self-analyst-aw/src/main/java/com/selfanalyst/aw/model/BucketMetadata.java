package com.selfanalyst.aw.model;

import java.util.Map;

public record BucketMetadata(
        String id,
        String name,
        String type,
        String client,
        String hostname,
        String created,
        String lastUpdated,
        int eventCount) {

    public static BucketMetadata fromBucket(Bucket bucket, int eventCount) {
        return new BucketMetadata(
                bucket.id(), bucket.name(), bucket.type(), bucket.client(),
                bucket.hostname(),
                bucket.created().toString(), bucket.lastUpdated().toString(),
                eventCount);
    }

    public Map<String, Object> toMap() {
        return Map.of(
                "id", id,
                "name", name,
                "type", type,
                "client", client,
                "hostname", hostname,
                "created", created,
                "last_updated", lastUpdated,
                "event_count", eventCount);
    }
}
