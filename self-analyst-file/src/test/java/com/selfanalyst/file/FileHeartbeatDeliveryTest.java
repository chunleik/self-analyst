package com.selfanalyst.file;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileHeartbeatDeliveryTest {

    @Test
    void networkRetriesReuseStableIdAndCarryMetadataOnly() {
        List<Map<String, Object>> requests = new ArrayList<>();
        AtomicInteger attempts = new AtomicInteger();
        Map<String, Object> metadata = Map.of(
                "path", "D:/docs/report.docx",
                "size_bytes", 42,
                "last_modified", "2026-09-03T12:00:00Z");

        boolean sent = new FileHeartbeatDelivery().send(metadata, Instant.EPOCH, 2, body -> {
            requests.add(body);
            return attempts.incrementAndGet() == 3;
        });

        assertTrue(sent);
        assertEquals(3, requests.size());
        Object sourceId = requests.getFirst().get("sourceEventId");
        assertFalse(sourceId.toString().isBlank());
        requests.forEach(request -> assertEquals(sourceId, request.get("sourceEventId")));
        String serialized = requests.toString();
        assertFalse(serialized.contains("file_content"));
        assertFalse(serialized.contains("content_hash"));
        assertFalse(serialized.contains("SELF_ANALYST_FILE_BODY_SECRET"));
    }
}
