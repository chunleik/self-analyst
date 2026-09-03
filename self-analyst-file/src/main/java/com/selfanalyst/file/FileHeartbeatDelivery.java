package com.selfanalyst.file;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** 已通过节流的文件元数据 heartbeat 的稳定 ID 与有界重试。 */
final class FileHeartbeatDelivery {
    private static final int MAX_ATTEMPTS = 3;

    boolean send(Map<String, Object> metadata, Instant timestamp,
                 double duration, Sender sender) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", timestamp.toString());
        body.put("duration", duration);
        body.put("sourceEventId", UUID.randomUUID().toString());
        body.put("data", Map.copyOf(metadata));
        Map<String, Object> immutable = Map.copyOf(body);
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                if (sender.send(immutable)) return true;
            } catch (Exception ignored) {
                // 下一次尝试复用同一个不可变请求与 sourceEventId。
            }
        }
        return false;
    }

    @FunctionalInterface
    interface Sender {
        boolean send(Map<String, Object> body) throws Exception;
    }
}
