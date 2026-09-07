package com.selfanalyst.events.raw;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/** 隐私与来源 schema 校验完成后、任何投影或合并之前的不可变事件。 */
public record RawEvent(
        String eventId,
        String sourceEventId,
        String bucketId,
        RawEventSource source,
        int schemaVersion,
        RawIngestKind ingestKind,
        Instant eventTimestamp,
        Instant receivedAt,
        double duration,
        String canonicalDataJson,
        String dataSha256,
        String importSessionId,
        Integer importOrdinal) {

    public RawEvent {
        requireText(eventId, "eventId");
        requireText(bucketId, "bucketId");
        Objects.requireNonNull(source, "source");
        if (schemaVersion <= 0) throw new IllegalArgumentException("schemaVersion 必须为正整数");
        Objects.requireNonNull(ingestKind, "ingestKind");
        Objects.requireNonNull(eventTimestamp, "eventTimestamp");
        Objects.requireNonNull(receivedAt, "receivedAt");
        if (!Double.isFinite(duration) || duration < 0) {
            throw new IllegalArgumentException("duration 必须是非负有限值");
        }
        requireText(canonicalDataJson, "canonicalDataJson");
        if (dataSha256 == null || !dataSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("dataSha256 必须是小写 SHA-256 十六进制值");
        }
        if (ingestKind == RawIngestKind.IMPORT) {
            requireText(importSessionId, "importSessionId");
            if (importOrdinal == null || importOrdinal < 0) {
                throw new IllegalArgumentException("importOrdinal 必须是非负整数");
            }
        } else if (importSessionId != null || importOrdinal != null) {
            throw new IllegalArgumentException("只有 import 事件可以携带导入身份");
        }
    }

    public static RawEvent create(RawEventIdGenerator idGenerator,
                                  String sourceEventId,
                                  String bucketId,
                                  RawEventSource source,
                                  int schemaVersion,
                                  RawIngestKind ingestKind,
                                  Instant eventTimestamp,
                                  Instant receivedAt,
                                  double duration,
                                  Map<String, ?> data,
                                  String importSessionId,
                                  Integer importOrdinal) {
        CanonicalJson.Value canonical = CanonicalJson.encode(data);
        return new RawEvent(
                Objects.requireNonNull(idGenerator, "idGenerator").nextId(),
                blankToNull(sourceEventId), bucketId, source, schemaVersion,
                ingestKind, eventTimestamp, receivedAt, duration,
                canonical.json(), canonical.sha256(),
                blankToNull(importSessionId), importOrdinal);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
