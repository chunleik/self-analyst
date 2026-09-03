package com.selfanalyst.aw.raw;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RawEventModelTest {

    @Test
    void canonicalJsonSortsRecursivelyAndProducesStableHash() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("z", List.of(1.0, new BigDecimal("2.5000")));
        first.put("a", new LinkedHashMap<>(Map.of("b", 1.00, "a", true)));

        Map<String, Object> second = new LinkedHashMap<>();
        second.put("a", new LinkedHashMap<>(Map.of("a", true, "b", 1)));
        second.put("z", List.of(1, new BigDecimal("2.5")));

        CanonicalJson.Value left = CanonicalJson.encode(first);
        CanonicalJson.Value right = CanonicalJson.encode(second);

        assertEquals("{\"a\":{\"a\":true,\"b\":1},\"z\":[1,2.5]}", left.json());
        assertEquals(left, right);
        assertEquals(64, left.sha256().length());
    }

    @Test
    void canonicalJsonRejectsNonFiniteNumbersAndNonStringKeys() {
        assertThrows(IllegalArgumentException.class,
                () -> CanonicalJson.encode(Map.of("bad", Double.NaN)));
        Map<Object, Object> invalid = new LinkedHashMap<>();
        invalid.put(1, "value");
        @SuppressWarnings({"rawtypes", "unchecked"})
        Map<String, ?> unsafe = (Map) invalid;
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.encode(unsafe));
    }

    @Test
    void generatedIdsAreUniqueFixedWidthAndTimeOrdered() {
        Clock fixed = Clock.fixed(Instant.parse("2026-09-03T12:00:00Z"), ZoneOffset.UTC);
        RawEventIdGenerator generator = new RawEventIdGenerator(fixed, new SecureRandom());

        String first = generator.nextId();
        String second = generator.nextId();

        assertEquals(26, first.length());
        assertNotEquals(first, second);
        assertTrue(first.compareTo(second) < 0, first + " >= " + second);
        assertTrue(first.matches("[0-9A-HJKMNP-TV-Z]{26}"), first);
    }

    @Test
    void rawEventFactoryKeepsOriginalSemanticsAndCanonicalIntegrity() {
        Instant eventAt = Instant.parse("2026-08-31T23:59:59Z");
        Instant receivedAt = Instant.parse("2026-09-01T00:00:01Z");
        RawEvent event = RawEvent.create(new RawEventIdGenerator(), "source-1", "bucket-1",
                RawEventSource.CONTENT, 2, RawIngestKind.HEARTBEAT,
                eventAt, receivedAt, 5.0, Map.of("title", "文档"), null, null);

        assertEquals("source-1", event.sourceEventId());
        assertEquals(eventAt, event.eventTimestamp());
        assertEquals(receivedAt, event.receivedAt());
        assertEquals("{\"title\":\"文档\"}", event.canonicalDataJson());
        assertEquals(CanonicalJson.encode(Map.of("title", "文档")).sha256(),
                event.dataSha256());
    }

    @Test
    void enumsUseStableStorageValuesAndImportIdentityIsComplete() {
        assertEquals(RawEventSource.CONTENT, RawEventSource.parse("content"));
        assertEquals(RawIngestKind.EVENTS, RawIngestKind.parse("events"));
        assertEquals("file", RawEventSource.FILE.storageValue());
        assertEquals("heartbeat", RawIngestKind.HEARTBEAT.storageValue());

        CanonicalJson.Value data = CanonicalJson.encode(Map.of());
        assertThrows(IllegalArgumentException.class, () -> new RawEvent(
                "id", null, "bucket", RawEventSource.IMPORT, 1,
                RawIngestKind.IMPORT, Instant.EPOCH, Instant.EPOCH, 0,
                data.json(), data.sha256(), null, null));
    }
}
