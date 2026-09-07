package com.selfanalyst.events.projection;

import com.selfanalyst.events.model.Bucket;
import com.selfanalyst.events.model.Event;
import com.selfanalyst.events.raw.RawEvent;
import com.selfanalyst.events.raw.RawEventAppender;
import com.selfanalyst.events.raw.RawEventIdGenerator;
import com.selfanalyst.events.raw.RawEventSource;
import com.selfanalyst.events.raw.RawIngestKind;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** `/events` 单条和批量请求的原始优先事务边界。 */
public final class EventIngestionService {
    private final RawEventAppender rawAppender;
    private final RawEventProjector projector;
    private final RawEventIdGenerator ids = new RawEventIdGenerator();
    private final Clock clock;

    public EventIngestionService(RawEventAppender rawAppender, RawEventProjector projector) {
        this(rawAppender, projector, Clock.systemUTC());
    }

    EventIngestionService(RawEventAppender rawAppender, RawEventProjector projector, Clock clock) {
        this.rawAppender = Objects.requireNonNull(rawAppender, "rawAppender");
        this.projector = Objects.requireNonNull(projector, "projector");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Result ingest(Bucket bucket, List<Submission> submissions) {
        if (submissions == null || submissions.isEmpty()) {
            return new Result(List.of(), false);
        }
        Instant receivedAt = Instant.now(clock);
        RawEventSource source = RawEventSource.fromBucket(bucket.id(), bucket.client());
        int schemaVersion = source == RawEventSource.CONTENT ? 2 : 1;
        List<RawEvent> rawEvents = new ArrayList<>(submissions.size());
        for (Submission submission : submissions) {
            rawEvents.add(RawEvent.create(ids, submission.sourceEventId(), bucket.id(),
                    source, schemaVersion, RawIngestKind.EVENTS,
                    submission.event().timestamp(), receivedAt, submission.event().duration(),
                    submission.event().data(), null, null));
        }
        List<RawEvent> stored = rawAppender.appendBatch(rawEvents);
        boolean pending = false;
        for (RawEvent event : stored) {
            try {
                projector.project(event);
            } catch (RuntimeException failure) {
                pending = true;
            }
        }
        return new Result(stored, pending);
    }

    public record Submission(Event event, String sourceEventId) {}
    public record Result(List<RawEvent> rawEvents, boolean projectionPending) {}
}
