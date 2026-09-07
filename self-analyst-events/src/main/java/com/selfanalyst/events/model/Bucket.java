package com.selfanalyst.events.model;

import java.time.Instant;

public record Bucket(
        String id,
        String name,
        String type,
        String client,
        String hostname,
        Instant created,
        Instant lastUpdated) {

    public static Bucket create(String id, String name, String type, String client, String hostname) {
        var now = Instant.now();
        return new Bucket(id, name, type, client, hostname, now, now);
    }

    public Bucket withUpdatedNow() {
        return new Bucket(id, name, type, client, hostname, created, Instant.now());
    }
}
