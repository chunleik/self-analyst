package com.selfanalyst.desktop.controller;

import com.selfanalyst.audio.AudioCaptureDiagnostics;
import com.selfanalyst.audio.AudioCaptureManager;
import com.selfanalyst.aw.model.Bucket;
import com.selfanalyst.aw.model.Event;
import com.selfanalyst.aw.store.BucketStore;
import com.selfanalyst.aw.store.Database;
import com.selfanalyst.aw.store.EventStore;
import com.selfanalyst.aw.store.PulseTimeConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DesktopAudioEventsControllerTest {

    @Test
    void listsAudioTranscriptsNewestFirstAndFiltersErrors(@TempDir Path dir) throws Exception {
        Database db = new Database(dir.resolve("aw-data"));
        try {
            BucketStore buckets = new BucketStore(db);
            EventStore events = new EventStore(db, PulseTimeConfig.DEFAULT);
            buckets.create(Bucket.create("aw-watcher-audio_test", "Audio watcher", "audio", "audio", "test"));
            buckets.create(Bucket.create("aw-watcher-window_test", "Window watcher", "currentwindow", "window", "test"));

            events.insertEvent("aw-watcher-audio_test", new Event(
                    Instant.parse("2026-07-05T13:00:00Z"), 5.0,
                    Map.of("text", "第一句", "engine", "WhisperEngine")));
            events.insertEvent("aw-watcher-audio_test", new Event(
                    Instant.parse("2026-07-05T13:00:05Z"), 5.0,
                    Map.of("text", "error: failed to initialize whisper context", "engine", "WhisperEngine")));
            events.insertEvent("aw-watcher-audio_test", new Event(
                    Instant.parse("2026-07-05T13:00:10Z"), 5.0,
                    Map.of("text", "第二句", "engine", "WhisperEngine", "source", "system")));
            events.insertEvent("aw-watcher-audio_test", new Event(
                    Instant.parse("2026-07-05T13:00:15Z"), 5.0,
                    Map.of("text", "read_audio_data: reading audio data from 'C:\\Users\\10478\\AppData\\Local\\Temp\\whisper_1.wav' ...read_audio_data: trying to decode with miniaudioAny.",
                            "engine", "WhisperEngine")));
            events.insertEvent("aw-watcher-audio_test", new Event(
                    Instant.parse("2026-07-05T13:00:20Z"), 5.0,
                    Map.of("text", "read_audio_data: reading audio data from 'C:\\Users\\10478\\AppData\\Local\\Temp\\whisper_2.wav' ...read_audio_data: trying to decode with miniaudio",
                            "engine", "WhisperEngine")));
            events.insertEvent("aw-watcher-window_test", new Event(
                    Instant.parse("2026-07-05T13:00:20Z"), 1.0,
                    Map.of("text", "窗口标题")));

            DesktopAudioEventsController.AudioEventsResponse response =
                    new DesktopAudioEventsController(events, buckets, null).getAudioEvents(10);

            assertEquals("disabled", response.status());
            assertEquals(1, response.bucketIds().size());
            assertEquals("aw-watcher-audio_test", response.bucketIds().get(0));
            assertEquals("2026-07-05T13:00:15Z", response.latestEventAt());
            assertEquals(3, response.eventCount());
            assertEquals(0, response.diagnostics().sampleCount());
            assertEquals(3, response.events().size());
            assertEquals("2026-07-05T13:00:15Z", response.events().get(0).timestamp());
            assertEquals("Any.", response.events().get(0).text());
            assertEquals("第二句", response.events().get(1).text());
            assertEquals("system", response.events().get(1).source());
            assertEquals("第一句", response.events().get(2).text());
        } finally {
            db.close();
        }
    }

    @Test
    void includesRuntimeAudioDiagnostics(@TempDir Path dir) throws Exception {
        Database db = new Database(dir.resolve("aw-data"));
        try {
            BucketStore buckets = new BucketStore(db);
            EventStore events = new EventStore(db, PulseTimeConfig.DEFAULT);
            buckets.create(Bucket.create("aw-watcher-audio_test", "Audio watcher", "audio", "audio", "test"));

            FakeWatcher watcher = new FakeWatcher();
            watcher.diagnostics = new AudioCaptureDiagnostics(
                    true, true, 0.003, 10, 8, 2, 1, 1,
                    "2026-07-05T14:00:00Z", "2026-07-05T14:00:00Z",
                    "2026-07-05T13:59:55Z", "2026-07-05T13:59:57Z",
                    "2026-07-05T13:59:50Z", null, null, 0.001);
            AudioCaptureManager manager = new AudioCaptureManager(() -> watcher, true);

            DesktopAudioEventsController.AudioEventsResponse response =
                    new DesktopAudioEventsController(events, buckets, manager).getAudioEvents(10);

            assertEquals("running", response.status());
            assertEquals(10, response.diagnostics().sampleCount());
            assertEquals(8, response.diagnostics().silentCount());
            assertEquals("2026-07-05T14:00:00Z", response.diagnostics().lastSilentAt());
        } finally {
            db.close();
        }
    }

    private static final class FakeWatcher implements AudioCaptureManager.WatcherHandle {
        AudioCaptureDiagnostics diagnostics = AudioCaptureDiagnostics.empty();

        @Override
        public void start() {}

        @Override
        public void shutdown() {}

        @Override
        public boolean isAlive() {
            return true;
        }

        @Override
        public AudioCaptureDiagnostics diagnostics() {
            return diagnostics;
        }
    }
}
