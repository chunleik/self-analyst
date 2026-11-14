package com.selfanalyst.observability;

import io.opentelemetry.sdk.trace.data.SpanData;

import java.io.IOException;
import java.time.Instant;
import java.util.*;

/** Official OTLP JSON mapping: hex IDs, decimal-string int64 values, numeric enums. */
final class ObservabilityOtlp {
    static byte[] encode(List<SpanData> spans) throws IOException {
        List<Map<String, Object>> result = new ArrayList<>();
        for (SpanData span : spans) {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("traceId", span.getTraceId());
            fields.put("spanId", span.getSpanId());
            if (!span.getParentSpanId().equals("0000000000000000"))
                fields.put("parentSpanId", span.getParentSpanId());
            fields.put("name", span.getName());
            fields.put("kind", span.getKind().ordinal() + 1);
            fields.put("startTimeUnixNano", Long.toString(span.getStartEpochNanos()));
            fields.put("endTimeUnixNano", Long.toString(span.getEndEpochNanos()));
            List<Map<String, Object>> attributes = new ArrayList<>();
            span.getAttributes()
                    .forEach(
                            (key, value) -> {
                                if (key.getKey().equals("selfanalyst.epoch")) return;
                                Map<String, Object> v =
                                        value instanceof Boolean
                                                ? Map.of("boolValue", value)
                                                : value instanceof Number
                                                        ? Map.of("intValue", value.toString())
                                                        : Map.of("stringValue", value.toString());
                                attributes.add(Map.of("key", key.getKey(), "value", v));
                            });
            fields.put("attributes", attributes);
            fields.put("status", Map.of("code", span.getStatus().getStatusCode().ordinal()));
            result.add(fields);
        }
        return envelope(result);
    }

    static byte[] synthetic() throws IOException {
        long now = Instant.now().toEpochMilli() * 1000000;
        return envelope(
                List.of(
                        Map.of(
                                "traceId",
                                UUID.randomUUID().toString().replace("-", ""),
                                "spanId",
                                UUID.randomUUID().toString().replace("-", "").substring(0, 16),
                                "name",
                                "selfanalyst.synthetic_test",
                                "kind",
                                1,
                                "startTimeUnixNano",
                                Long.toString(now),
                                "endTimeUnixNano",
                                Long.toString(now + 1),
                                "attributes",
                                List.of(),
                                "status",
                                Map.of("code", 1))));
    }

    private static byte[] envelope(List<Map<String, Object>> spans) throws IOException {
        return ObservabilityStore.JSON.writeValueAsBytes(
                Map.of(
                        "resourceSpans",
                        List.of(
                                Map.of(
                                        "resource",
                                        Map.of(
                                                "attributes",
                                                List.of(
                                                        Map.of(
                                                                "key",
                                                                "service.name",
                                                                "value",
                                                                Map.of(
                                                                        "stringValue",
                                                                        "selfanalyst")))),
                                        "scopeSpans",
                                        List.of(
                                                Map.of(
                                                        "scope",
                                                        Map.of("name", "selfanalyst.observability"),
                                                        "spans",
                                                        spans))))));
    }
}
