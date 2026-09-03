package com.selfanalyst.aw.projection;

import com.selfanalyst.aw.model.Bucket;
import com.selfanalyst.aw.model.Event;
import com.selfanalyst.aw.raw.ProjectionStatus;
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

class HeartbeatIngestionServiceTest {

    private static final Bucket BUCKET = new Bucket("bucket", "bucket", "window",
            "window-watcher", "host", Instant.EPOCH, Instant.EPOCH);
    private static final Event EVENT = new Event(Instant.EPOCH, 5, Map.of("app", "editor"));

    @Test
    void rawCommitPrecedesProjectionAndProjectionFailureBecomesPending() {
        List<RawEvent> stored = new ArrayList<>();
        RawEventAppender appender = new ListAppender(stored);
        HeartbeatIngestionService service = new HeartbeatIngestionService(appender,
                event -> { throw new IllegalStateException("projection failed"); });

        var result = service.ingest(BUCKET, EVENT, "source-1");

        assertEquals(1, stored.size());
        assertEquals(ProjectionStatus.PENDING, result.projectionStatus());
        assertEquals(stored.getFirst().eventId(), result.rawEvent().eventId());
    }

    @Test
    void rawFailureNeverCallsProjector() {
        AtomicInteger projections = new AtomicInteger();
        HeartbeatIngestionService service = new HeartbeatIngestionService(
                new ListAppender(null), event -> {
                    projections.incrementAndGet();
                    return null;
                });

        assertThrows(IllegalStateException.class,
                () -> service.ingest(BUCKET, EVENT, "source-1"));
        assertEquals(0, projections.get());
    }

    private record ListAppender(List<RawEvent> events) implements RawEventAppender {
        @Override public RawEvent append(RawEvent event) {
            if (events == null) throw new IllegalStateException("raw failed");
            events.add(event);
            return event;
        }
        @Override public List<RawEvent> appendBatch(List<RawEvent> events) {
            throw new UnsupportedOperationException();
        }
    }
}
