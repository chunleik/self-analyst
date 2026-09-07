package com.selfanalyst.events.model;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.Map;

public record ServerInfo(
        String version,
        String hostname,
        String startedAt) {

    public static ServerInfo create() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            host = "unknown";
        }
        return new ServerInfo("1.0.0-java", host, Instant.now().toString());
    }

    public Map<String, Object> toMap() {
        return Map.of(
                "version", version,
                "hostname", hostname,
                "started_at", startedAt);
    }
}
