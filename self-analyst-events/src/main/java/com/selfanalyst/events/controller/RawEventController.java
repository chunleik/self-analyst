package com.selfanalyst.events.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfanalyst.events.raw.RawEvent;
import com.selfanalyst.events.raw.RawEventQueryService;
import io.javalin.http.Context;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

/** 受桌面认证保护的有界原始事件查询端点。 */
public final class RawEventController {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final RawEventQueryService queries;

    public RawEventController(RawEventQueryService queries) {
        this.queries = queries;
    }

    public void query(Context ctx) {
        try {
            String bucketId = required(ctx, "bucketId");
            Instant start = Instant.parse(required(ctx, "start"));
            Instant end = Instant.parse(required(ctx, "end"));
            int limit = ctx.queryParam("limit") == null
                    ? 100 : Integer.parseInt(ctx.queryParam("limit"));
            var page = queries.query(bucketId, start, end, limit, ctx.queryParam("cursor"));
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("events", page.events().stream().map(RawEventController::eventMap).toList());
            response.put("nextCursor", page.nextCursor());
            response.put("coverage", Map.of("partitions", page.scannedPartitions()));
            ctx.json(response);
        } catch (IllegalArgumentException | NullPointerException | DateTimeParseException invalid) {
            ctx.status(400).json(Map.of("error", invalid.getMessage() == null
                    ? "原始事件查询参数无效" : invalid.getMessage()));
        } catch (Exception failure) {
            ctx.status(500).json(Map.of("error", "原始事件查询失败"));
        }
    }

    private static String required(Context ctx, String name) {
        String value = ctx.queryParam(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " 必填");
        return value;
    }

    private static Map<String, Object> eventMap(RawEvent event) {
        try {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("eventId", event.eventId());
            result.put("sourceEventId", event.sourceEventId());
            result.put("bucketId", event.bucketId());
            result.put("source", event.source().storageValue());
            result.put("schemaVersion", event.schemaVersion());
            result.put("ingestKind", event.ingestKind().storageValue());
            result.put("eventTimestamp", event.eventTimestamp().toString());
            result.put("receivedAt", event.receivedAt().toString());
            result.put("duration", event.duration());
            result.put("data", MAPPER.readValue(event.canonicalDataJson(),
                    new TypeReference<Map<String, Object>>() {}));
            result.put("dataSha256", event.dataSha256());
            result.put("importSessionId", event.importSessionId());
            result.put("importOrdinal", event.importOrdinal());
            return result;
        } catch (Exception failure) {
            throw new IllegalStateException("原始事件规范化 JSON 无法读取", failure);
        }
    }
}
