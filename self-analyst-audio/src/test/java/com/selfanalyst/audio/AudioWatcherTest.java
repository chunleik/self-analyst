package com.selfanalyst.audio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.time.Instant;
import java.util.Map;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AudioWatcherTest {

    @Test
    void missingWhisperFilesCreateUnavailableEngine(@TempDir Path tempDir) {
        AudioEngine engine = AudioWatcher.createEngine(tempDir);

        assertFalse(engine.isAvailable());
    }

    @Test
    @SuppressWarnings("unchecked")
    void heartbeatDurationUsesConfiguredChunkSeconds() {
        AudioCapturer.CaptureResult capture = new AudioCapturer.CaptureResult(
                new byte[] {1, 2, 3}, true, 0.02f, "mic", "test mic");

        Map<String, Object> body = AudioWatcher.heartbeatBody(
                "hello", "test-engine", capture, 12, Instant.parse("2026-07-05T15:00:00Z"));

        assertEquals(12.0, body.get("duration"));
        assertEquals("2026-07-05T15:00:00Z", body.get("timestamp"));
        Map<String, Object> data = (Map<String, Object>) body.get("data");
        assertEquals("mic", data.get("source"));
        assertEquals("test mic", data.get("device"));
    }
}
