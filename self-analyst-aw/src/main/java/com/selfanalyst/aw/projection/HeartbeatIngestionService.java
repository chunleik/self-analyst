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
        RawEvent raw = RawEvent.create(ids, sourceEventId, bucket.id(), sourceOf(bucket),
                schemaVersion(bucket), RawIngestKind.HEARTBEAT,
                event.timestamp(), Instant.now(clock), event.duration(), event.data(), null, null);
        RawEvent stored = rawAppender.append(raw);
        try {
            EventProjector.ProjectionResult projection = projector.project(stored);
            return new Result(stored, projection.projectionEventId(), ProjectionStatus.PROJECTED);
        } catch (RuntimeException projectionFailure) {
            return new Result(stored, null, ProjectionStatus.PENDING);
        }
    }

    private static RawEventSource sourceOf(Bucket bucket) {
        String id = bucket.id().toLowerCase(java.util.Locale.ROOT);
        String client = bucket.client().toLowerCase(java.util.Locale.ROOT);
        if (id.startsWith("aw-watcher-window_") || client.contains("window")) return RawEventSource.WINDOW;
        if (id.startsWith("aw-watcher-afk_") || client.contains("afk")) return RawEventSource.AFK;
        if (id.startsWith("aw-watcher-content_") || client.contains("content")) return RawEventSource.CONTENT;
        if (id.startsWith("aw-watcher-file_") || client.contains("file")) return RawEventSource.FILE;
        return RawEventSource.THIRD_PARTY;
    }

    private static int schemaVersion(Bucket bucket) {
        return sourceOf(bucket) == RawEventSource.CONTENT ? 2 : 1;
    }

    public record Result(RawEvent rawEvent, Long projectionEventId,
                         ProjectionStatus projectionStatus) {}
}
