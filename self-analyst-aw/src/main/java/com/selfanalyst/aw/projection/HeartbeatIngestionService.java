package com.selfanalyst.aw.projection;

import com.selfanalyst.aw.model.Bucket;
import com.selfanalyst.aw.model.Event;
import com.selfanalyst.aw.raw.ProjectionStatus;
import com.selfanalyst.aw.raw.RawEvent;
import com.selfanalyst.aw.raw.RawEventAppender;
import com.selfanalyst.aw.raw.RawEventIdGenerator;
import com.selfanalyst.aw.raw.RawEventSource;
import com.selfanalyst.aw.raw.RawIngestKind;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/** heartbeat 的“已校验事件 → raw 提交 → 投影”边界。 */
public final class HeartbeatIngestionService {

    private final RawEventAppender rawAppender;
    private final RawEventProjector projector;
    private final RawEventIdGenerator ids;
    private final Clock clock;

    public HeartbeatIngestionService(RawEventAppender rawAppender,
                                     RawEventProjector projector) {
        this(rawAppender, projector, new RawEventIdGenerator(), Clock.systemUTC());
    }

    HeartbeatIngestionService(RawEventAppender rawAppender, RawEventProjector projector,
                              RawEventIdGenerator ids, Clock clock) {
        this.rawAppender = Objects.requireNonNull(rawAppender, "rawAppender");
        this.projector = Objects.requireNonNull(projector, "projector");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Result ingest(Bucket bucket, Event event, String sourceEventId) {
        Objects.requireNonNull(bucket, "bucket");
        Objects.requireNonNull(event, "event");
        RawEventSource source = RawEventSource.fromBucket(bucket.id(), bucket.client());
        int schemaVersion = source == RawEventSource.CONTENT ? 2 : 1;
        RawEvent raw = RawEvent.create(ids, sourceEventId, bucket.id(), source,
                schemaVersion, RawIngestKind.HEARTBEAT,
                event.timestamp(), Instant.now(clock), event.duration(), event.data(), null, null);
        RawEvent stored = rawAppender.append(raw);
        try {
            EventProjector.ProjectionResult projection = projector.project(stored);
            return new Result(stored, projection.projectionEventId(), ProjectionStatus.PROJECTED);
        } catch (RuntimeException projectionFailure) {
            return new Result(stored, null, ProjectionStatus.PENDING);
        }
    }

    public record Result(RawEvent rawEvent, Long projectionEventId,
                         ProjectionStatus projectionStatus) {}
}
