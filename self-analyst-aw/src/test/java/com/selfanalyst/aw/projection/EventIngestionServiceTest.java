package com.selfanalyst.aw.projection;

import com.selfanalyst.aw.model.Bucket;
import com.selfanalyst.aw.model.Event;
import com.selfanalyst.aw.raw.RawEvent;
import com.selfanalyst.aw.raw.RawEventAppender;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EventIngestionServiceTest {
    private static final Bucket BUCKET = new Bucket("bucket", "bucket", "test",
            "test", "host", Instant.EPOCH, Instant.EPOCH);

    @Test
    void wholeRawBatchCommitsBeforeAnyProjection() {
        List<RawEvent> stored = new ArrayList<>();
        AtomicInteger projections = new AtomicInteger();
        EventIngestionService service = new EventIngestionService(
                new BatchAppender(stored, false), event -> {
                    assertEquals(2, stored.size());
                    projections.incrementAndGet();
                    return null;
                });
        var result = service.ingest(BUCKET, submissions());
        assertEquals(2, stored.size());
        assertEquals(2, projections.get());
        assertEquals(2, result.rawEvents().size());
    }

    @Test
    void rawBatchFailureProducesNoProjectionOrPartialRows() {
        List<RawEvent> stored = new ArrayList<>();
        AtomicInteger projections = new AtomicInteger();
        EventIngestionService service = new EventIngestionService(
                new BatchAppender(stored, true), event -> {
                    projections.incrementAndGet(); return null;
                });
        assertThrows(IllegalStateException.class, () -> service.ingest(BUCKET, submissions()));
        assertEquals(0, stored.size());
        assertEquals(0, projections.get());
    }

    private static List<EventIngestionService.Submission> submissions() {
        return List.of(
                new EventIngestionService.Submission(
                        new Event(Instant.EPOCH, 1, Map.of("value", 1)), "one"),
                new EventIngestionService.Submission(
                        new Event(Instant.EPOCH.plusSeconds(1), 1, Map.of("value", 2)), "two"));
    }

    private record BatchAppender(List<RawEvent> target, boolean fail) implements RawEventAppender {
        @Override public RawEvent append(RawEvent event) { throw new UnsupportedOperationException(); }
        @Override public List<RawEvent> appendBatch(List<RawEvent> events) {
            if (fail) throw new IllegalStateException("raw batch failed");
            target.addAll(events);
            return List.copyOf(events);
        }
    }
}
