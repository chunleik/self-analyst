package com.selfanalyst.audio;

/**
 * Runtime control for microphone capture.
 * <p>
 * AudioWatcher extends Thread, so a stopped watcher cannot be started again.
 * This manager owns that lifecycle and creates a fresh watcher for each new
 * enable request after a stop.
 */
public class AudioCaptureManager {

    @FunctionalInterface
    public interface WatcherFactory {
        WatcherHandle create();
    }

    public interface WatcherHandle {
        void start();
        void shutdown();
        boolean isAlive();

        default String status() {
            return isAlive() ? "running" : "degraded";
        }

        default AudioCaptureDiagnostics diagnostics() {
            return AudioCaptureDiagnostics.empty();
        }
    }

    public record AudioCaptureState(
            boolean enabled,
            String status,
            AudioCaptureDiagnostics diagnostics) {}

    private final WatcherFactory factory;
    private WatcherHandle watcher;
    private boolean enabled;

    public AudioCaptureManager(String serverUrl, boolean enabledInitially) {
        this(serverUrl, enabledInitially, java.nio.file.Path.of("tools/whisper"), 0.0001f);
    }

    public AudioCaptureManager(String serverUrl, boolean enabledInitially, java.nio.file.Path whisperPath) {
        this(serverUrl, enabledInitially, whisperPath, 0.0001f);
    }

    public AudioCaptureManager(String serverUrl, boolean enabledInitially,
                               java.nio.file.Path whisperPath, double vadThreshold) {
        this(serverUrl, enabledInitially, AudioCaptureOptions.localDefaults(whisperPath, vadThreshold));
    }

    public AudioCaptureManager(String serverUrl, boolean enabledInitially, AudioCaptureOptions options) {
        this(factory(serverUrl, options), enabledInitially);
    }

    public AudioCaptureManager(WatcherFactory factory, boolean enabledInitially) {
        this.factory = factory;
        if (enabledInitially) {
            setEnabled(true);
        }
    }

    public synchronized AudioCaptureState setEnabled(boolean nextEnabled) {
        if (nextEnabled) {
            enabled = true;
            if (watcher == null || !watcher.isAlive()) {
                watcher = factory.create();
                watcher.start();
            }
        } else {
            enabled = false;
            if (watcher != null) {
                watcher.shutdown();
                watcher = null;
            }
        }
        return status();
    }

    public synchronized AudioCaptureState status() {
        if (watcher != null && enabled) {
            return new AudioCaptureState(true, normalizeStatus(watcher.status()), watcher.diagnostics());
        }
        if (enabled) {
            return new AudioCaptureState(true, "degraded", AudioCaptureDiagnostics.empty());
        }
        return new AudioCaptureState(false, "disabled", AudioCaptureDiagnostics.empty());
    }

    public synchronized void shutdown() {
        setEnabled(false);
    }

    private static String normalizeStatus(String status) {
        return "running".equals(status) ? "running" : "degraded";
    }

    private static WatcherFactory factory(String serverUrl, AudioCaptureOptions options) {
        if ("both".equalsIgnoreCase(options.source())) {
            AudioCaptureOptions mic = new AudioCaptureOptions(
                    options.whisperPath(), options.vadThreshold(), options.chunkSeconds(),
                    "mic", options.engine(), options.cloudBaseUrl(),
                    options.cloudApiKey(), options.cloudModel());
            AudioCaptureOptions system = new AudioCaptureOptions(
                    options.whisperPath(), options.vadThreshold(), options.chunkSeconds(),
                    "system", options.engine(), options.cloudBaseUrl(),
                    options.cloudApiKey(), options.cloudModel());
            return () -> new CompositeWatcherHandle(
                    watcherHandle(serverUrl, mic), watcherHandle(serverUrl, system));
        }
        return () -> watcherHandle(serverUrl, options);
    }

    private static WatcherHandle watcherHandle(String serverUrl, AudioCaptureOptions options) {
        AudioWatcher watcher = new AudioWatcher(serverUrl, options);
        return new WatcherHandle() {
            @Override
            public void start() {
                watcher.start();
            }

            @Override
            public void shutdown() {
                watcher.shutdown();
            }

            @Override
            public boolean isAlive() {
                return watcher.isAlive();
            }

            @Override
            public String status() {
                return watcher.isAlive() && "running".equals(watcher.status())
                        ? "running"
                        : "degraded";
            }

            @Override
            public AudioCaptureDiagnostics diagnostics() {
                return watcher.diagnostics();
            }
        };
    }

    private static final class CompositeWatcherHandle implements WatcherHandle {
        private final WatcherHandle mic;
        private final WatcherHandle system;

        private CompositeWatcherHandle(WatcherHandle mic, WatcherHandle system) {
            this.mic = mic;
            this.system = system;
        }

        @Override
        public void start() {
            mic.start();
            system.start();
        }

        @Override
        public void shutdown() {
            mic.shutdown();
            system.shutdown();
        }

        @Override
        public boolean isAlive() {
            return mic.isAlive() || system.isAlive();
        }

        @Override
        public String status() {
            return "running".equals(mic.status()) || "running".equals(system.status())
                    ? "running"
                    : "degraded";
        }

        @Override
        public AudioCaptureDiagnostics diagnostics() {
            AudioCaptureDiagnostics a = mic.diagnostics();
            AudioCaptureDiagnostics b = system.diagnostics();
            AudioCaptureDiagnostics latest = latest(a, b);
            String lastErrorAt = maxTime(a.lastErrorAt(), b.lastErrorAt());
            String lastError = errorFor(lastErrorAt, a, b);
            return new AudioCaptureDiagnostics(
                    a.microphoneAvailable() || b.microphoneAvailable(),
                    a.engineAvailable() || b.engineAvailable(),
                    "both",
                    latest.engine(),
                    latest.deviceName(),
                    latest.availableInputDevices(),
                    Math.max(a.vadThreshold(), b.vadThreshold()),
                    a.sampleCount() + b.sampleCount(),
                    a.silentCount() + b.silentCount(),
                    a.voiceCount() + b.voiceCount(),
                    a.emptyTranscriptCount() + b.emptyTranscriptCount(),
                    a.transcriptCount() + b.transcriptCount(),
                    maxTime(a.lastSampleAt(), b.lastSampleAt()),
                    maxTime(a.lastSilentAt(), b.lastSilentAt()),
                    maxTime(a.lastVoiceAt(), b.lastVoiceAt()),
                    maxTime(a.lastEmptyTranscriptAt(), b.lastEmptyTranscriptAt()),
                    maxTime(a.lastTranscriptAt(), b.lastTranscriptAt()),
                    lastErrorAt,
                    lastError,
                    latest.lastRms());
        }

        private static AudioCaptureDiagnostics latest(AudioCaptureDiagnostics a, AudioCaptureDiagnostics b) {
            String ta = maxTime(a.lastSampleAt(), a.lastErrorAt());
            String tb = maxTime(b.lastSampleAt(), b.lastErrorAt());
            if (ta == null) return b;
            if (tb == null) return a;
            return ta.compareTo(tb) >= 0 ? a : b;
        }

        private static String maxTime(String a, String b) {
            if (a == null || a.isBlank()) return b;
            if (b == null || b.isBlank()) return a;
            return a.compareTo(b) >= 0 ? a : b;
        }

        private static String errorFor(String at, AudioCaptureDiagnostics a, AudioCaptureDiagnostics b) {
            if (at == null) return null;
            if (at.equals(a.lastErrorAt())) return a.lastError();
            if (at.equals(b.lastErrorAt())) return b.lastError();
            return null;
        }
    }
}
