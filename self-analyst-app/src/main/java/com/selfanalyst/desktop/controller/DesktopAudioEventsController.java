package com.selfanalyst.desktop.controller;

import com.selfanalyst.audio.AudioCaptureManager;
import com.selfanalyst.audio.AudioCaptureDiagnostics;
import com.selfanalyst.audio.WhisperEngine;
import com.selfanalyst.aw.model.Bucket;
import com.selfanalyst.aw.model.Event;
import com.selfanalyst.aw.store.BucketStore;
import com.selfanalyst.aw.store.EventStore;
import io.javalin.http.Context;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Desktop-facing audio transcript feed.
 */
public class DesktopAudioEventsController {

    private final EventStore eventStore;
    private final BucketStore bucketStore;
    private final AudioCaptureManager audioCaptureManager;

    public DesktopAudioEventsController(EventStore eventStore,
                                        BucketStore bucketStore,
                                        AudioCaptureManager audioCaptureManager) {
        this.eventStore = eventStore;
        this.bucketStore = bucketStore;
        this.audioCaptureManager = audioCaptureManager;
    }

    public void getEvents(Context ctx) {
        int limit = parseLimit(ctx.queryParam("limit"));
        ctx.json(getAudioEvents(limit));
    }

    AudioEventsResponse getAudioEvents(int limit) {
        int cappedLimit = Math.max(1, Math.min(limit, 200));
        List<String> bucketIds = audioBucketIds();
        List<AudioTranscriptEvent> events = new ArrayList<>();
        for (String bucketId : bucketIds) {
            for (Event event : eventStore.queryAllEvents(bucketId)) {
                AudioTranscriptEvent transcript = toTranscript(bucketId, event);
                if (transcript != null) {
                    events.add(transcript);
                }
            }
        }
        events.sort(Comparator.comparing((AudioTranscriptEvent event) ->
                Instant.parse(event.timestamp())).reversed());
        String latestEventAt = events.isEmpty() ? null : events.get(0).timestamp();
        int eventCount = events.size();
        if (events.size() > cappedLimit) {
            events = new ArrayList<>(events.subList(0, cappedLimit));
        }
        AudioCaptureManager.AudioCaptureState state = audioCaptureState();
        return new AudioEventsResponse(
                state.status(), state.diagnostics(), latestEventAt, eventCount, bucketIds, events);
    }

    private List<String> audioBucketIds() {
        if (bucketStore == null) return List.of();
        return bucketStore.listAll().stream()
                .filter(DesktopAudioEventsController::isAudioBucket)
                .map(Bucket::id)
                .sorted()
                .toList();
    }

    private static boolean isAudioBucket(Bucket bucket) {
        return bucket.id().startsWith("aw-watcher-audio")
                || "audio".equalsIgnoreCase(bucket.type())
                || "audio".equalsIgnoreCase(bucket.client());
    }

    private AudioTranscriptEvent toTranscript(String bucketId, Event event) {
        Object rawText = event.data().get("text");
        String text = rawText == null ? "" : WhisperEngine.extractTranscript(String.valueOf(rawText));
        if (text.isBlank() || isErrorText(text)) {
            return null;
        }
        Object rawEngine = event.data().get("engine");
        String engine = rawEngine == null ? "" : String.valueOf(rawEngine);
        Object rawSource = event.data().get("source");
        String source = rawSource == null ? "mic" : String.valueOf(rawSource);
        return new AudioTranscriptEvent(
                event.id(), bucketId, event.timestamp().toString(), event.duration(), text, engine, source);
    }

    private static boolean isErrorText(String text) {
        String lower = text.toLowerCase();
        return lower.startsWith("error:")
                || lower.contains("failed to initialize whisper context");
    }

    private AudioCaptureManager.AudioCaptureState audioCaptureState() {
        if (audioCaptureManager == null) {
            return new AudioCaptureManager.AudioCaptureState(
                    false, "disabled", AudioCaptureDiagnostics.empty());
        }
        return audioCaptureManager.status();
    }

    private static int parseLimit(String raw) {
        if (raw == null || raw.isBlank()) return 50;
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return 50;
        }
    }

    public record AudioEventsResponse(
            String status,
            AudioCaptureDiagnostics diagnostics,
            String latestEventAt,
            int eventCount,
            List<String> bucketIds,
            List<AudioTranscriptEvent> events) {}

    public record AudioTranscriptEvent(
            long id,
            String bucketId,
            String timestamp,
            double duration,
            String text,
            String engine,
            String source) {}
}
