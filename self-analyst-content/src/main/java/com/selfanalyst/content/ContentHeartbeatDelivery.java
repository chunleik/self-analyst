package com.selfanalyst.content;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** 内容 heartbeat 的稳定来源身份和最多三次投递状态。 */
final class ContentHeartbeatDelivery {
    private static final int MAX_ATTEMPTS = 3;
    private Pending pending;
    private String session = UUID.randomUUID().toString();
    private Instant lastRequest;
    private boolean broken;


    Map<String, Object> request(Map<String, Object> allowedData,
                                Instant timestamp, double duration) {
        if (lastRequest != null && (timestamp.isBefore(lastRequest)
                || java.time.Duration.between(lastRequest, timestamp).toSeconds() > 10)) {
            broken = true;
            if (java.time.Duration.between(lastRequest, timestamp).toHours() >= 24) pending = null;
        }
        lastRequest = timestamp;
        if (pending == null) {
            if (broken) { session = UUID.randomUUID().toString(); broken = false; }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("timestamp", timestamp.toString());
            body.put("duration", duration);
            body.put("sourceEventId", UUID.randomUUID().toString());
            body.put("captureSessionId", session);
            body.put("data", Map.copyOf(allowedData));
            pending = new Pending(Map.copyOf(body), 0);
        }
        return pending.body();
    }

    void complete(boolean success) {
        if (pending == null) return;
        if (!success) broken = true;
        if (success || pending.attempts() + 1 >= MAX_ATTEMPTS) {
            pending = null;
        } else {
            pending = new Pending(pending.body(), pending.attempts() + 1);
        }
    }

    private record Pending(Map<String, Object> body, int attempts) {}
}
