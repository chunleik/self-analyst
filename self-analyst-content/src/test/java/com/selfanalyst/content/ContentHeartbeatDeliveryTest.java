package com.selfanalyst.content;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ContentHeartbeatDeliveryTest {

    @Test
    void retriesReuseIdAndRequestContainsOnlyAllowedTitleFacts() {
        ContentHeartbeatDelivery delivery = new ContentHeartbeatDelivery();
        Map<String, Object> allowed = ContentWatcher.heartbeatData(
                "editor.exe", "Document", null);
        Map<String, Object> first = delivery.request(
                allowed, Instant.EPOCH, 2);
        delivery.complete(false);
        Map<String, Object> retry = delivery.request(
                Map.of("forbidden", "SELF_ANALYST_FORBIDDEN_BODY_7F3A"),
                Instant.EPOCH.plusSeconds(10), 2);

        assertEquals(first.get("sourceEventId"), retry.get("sourceEventId"));
        assertEquals(first, retry);
        String serialized = retry.toString();
        assertFalse(serialized.contains("text_content"));
        assertFalse(serialized.contains("raw_tree"));
        assertFalse(serialized.contains("SELF_ANALYST_FORBIDDEN_BODY_7F3A"));
    }

    @Test
    void fourthLogicalRequestGetsNewIdAfterThreeFailures() {
        ContentHeartbeatDelivery delivery = new ContentHeartbeatDelivery();
        Map<String, Object> data = Map.of("schema_version", 2, "title", "safe");
        Object firstId = delivery.request(data, Instant.EPOCH, 2).get("sourceEventId");
        delivery.complete(false);
        delivery.request(data, Instant.EPOCH, 2);
        delivery.complete(false);
        delivery.request(data, Instant.EPOCH, 2);
        delivery.complete(false);

        Object nextId = delivery.request(data, Instant.EPOCH, 2).get("sourceEventId");
        assertFalse(firstId.equals(nextId));
    }
}
