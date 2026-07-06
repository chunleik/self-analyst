package com.selfanalyst.audio;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudioCaptureManagerTest {

    @Test
    void enablesDisablesAndReenablesWithFreshWatcher() {
        FakeWatcherFactory factory = new FakeWatcherFactory();
        AudioCaptureManager manager = new AudioCaptureManager(factory, false);

        assertFalse(manager.status().enabled());
        assertEquals("disabled", manager.status().status());

        AudioCaptureManager.AudioCaptureState enabled = manager.setEnabled(true);

        assertTrue(enabled.enabled());
        assertEquals("running", enabled.status());
        assertEquals(1, factory.watchers.size());
        assertTrue(factory.watchers.get(0).started);

        AudioCaptureManager.AudioCaptureState disabled = manager.setEnabled(false);

        assertFalse(disabled.enabled());
        assertEquals("disabled", disabled.status());
        assertTrue(factory.watchers.get(0).stopped);

        AudioCaptureManager.AudioCaptureState reenabled = manager.setEnabled(true);

        assertTrue(reenabled.enabled());
        assertEquals("running", reenabled.status());
        assertEquals(2, factory.watchers.size());
        assertTrue(factory.watchers.get(1).started);
        assertFalse(factory.watchers.get(1).stopped);
    }

    @Test
    void enableIsIdempotentWhileWatcherIsRunning() {
        FakeWatcherFactory factory = new FakeWatcherFactory();
        AudioCaptureManager manager = new AudioCaptureManager(factory, false);

        manager.setEnabled(true);
        manager.setEnabled(true);

        assertEquals(1, factory.watchers.size());
        assertEquals("running", manager.status().status());
    }

    @Test
    void reportsDegradedWatcherStatusWhileEnabled() {
        FakeWatcherFactory factory = new FakeWatcherFactory();
        AudioCaptureManager manager = new AudioCaptureManager(factory, false);

        manager.setEnabled(true);
        factory.watchers.get(0).status = "degraded";

        assertTrue(manager.status().enabled());
        assertEquals("degraded", manager.status().status());
    }

    @Test
    void includesWatcherDiagnosticsWhileEnabled() {
        FakeWatcherFactory factory = new FakeWatcherFactory();
        AudioCaptureManager manager = new AudioCaptureManager(factory, false);

        manager.setEnabled(true);
        factory.watchers.get(0).diagnostics = new AudioCaptureDiagnostics(
                true, true, 0.003, 4, 3, 1, 1, 0,
                "2026-07-05T14:00:00Z", "2026-07-05T14:00:00Z",
                "2026-07-05T13:59:55Z", null,
                "2026-07-05T13:59:55Z", null, null, 0.0012);

        AudioCaptureManager.AudioCaptureState state = manager.status();

        assertEquals(4, state.diagnostics().sampleCount());
        assertEquals(3, state.diagnostics().silentCount());
        assertEquals("2026-07-05T14:00:00Z", state.diagnostics().lastSampleAt());
    }

    private static final class FakeWatcherFactory implements AudioCaptureManager.WatcherFactory {
        final List<FakeWatcher> watchers = new ArrayList<>();

        @Override
        public AudioCaptureManager.WatcherHandle create() {
            FakeWatcher watcher = new FakeWatcher();
            watchers.add(watcher);
            return watcher;
        }
    }

    private static final class FakeWatcher implements AudioCaptureManager.WatcherHandle {
        boolean started;
        boolean stopped;
        String status = "running";
        AudioCaptureDiagnostics diagnostics = AudioCaptureDiagnostics.empty();

        @Override
        public void start() {
            started = true;
        }

        @Override
        public void shutdown() {
            stopped = true;
        }

        @Override
        public boolean isAlive() {
            return started && !stopped;
        }

        @Override
        public String status() {
            return status;
        }

        @Override
        public AudioCaptureDiagnostics diagnostics() {
            return diagnostics;
        }
    }
}
