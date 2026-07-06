package com.selfanalyst.audio;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Audio capture + transcription watcher.
 * Records chunks → VAD → speech-to-text transcription → push to AW.
 */
public class AudioWatcher extends Thread {

    private static final Logger log = LoggerFactory.getLogger(AudioWatcher.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private volatile AudioCapturer capturer;
    private final AudioEngine engine;
    private final String serverUrl;
    private final String bucketId;
    private final HttpClient httpClient;
    private final float vadThreshold;
    private final int chunkSeconds;
    private final String source;
    private volatile boolean running = true;
    private final AtomicLong sampleCount = new AtomicLong();
    private final AtomicLong silentCount = new AtomicLong();
    private final AtomicLong voiceCount = new AtomicLong();
    private final AtomicLong emptyTranscriptCount = new AtomicLong();
    private final AtomicLong transcriptCount = new AtomicLong();
    private volatile String lastSampleAt;
    private volatile String lastSilentAt;
    private volatile String lastVoiceAt;
    private volatile String lastEmptyTranscriptAt;
    private volatile String lastTranscriptAt;
    private volatile String lastErrorAt;
    private volatile String lastError;
    private volatile double lastRms;

    public AudioWatcher(String serverUrl) {
        this(serverUrl, Path.of("tools/whisper"));
    }

    public AudioWatcher(String serverUrl, Path whisperDir) {
        this(serverUrl, whisperDir, 0.0001f);
    }

    public AudioWatcher(String serverUrl, Path whisperDir, float vadThreshold) {
        this(serverUrl, AudioCaptureOptions.localDefaults(whisperDir, vadThreshold));
    }

    public AudioWatcher(String serverUrl, AudioCaptureOptions options) {
        if (options == null) {
            options = AudioCaptureOptions.localDefaults(Path.of("tools/whisper"), 0.0001f);
        }
        this.serverUrl = serverUrl;
        this.vadThreshold = (float) options.vadThreshold();
        this.chunkSeconds = options.chunkSeconds();
        this.source = options.source();
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        this.bucketId = "aw-watcher-audio_" + options.source() + "_" + getHostname();
        this.engine = createEngine(options);
        setDaemon(true);
        setName("audio-watcher-" + options.source());
    }

    static AudioEngine createEngine(Path whisperDir) {
        return createLocalEngine(whisperDir);
    }

    static AudioEngine createEngine(AudioCaptureOptions options) {
        AudioEngine local = createLocalEngine(options.whisperPath());
        AudioEngine cloud = new CloudAudioEngine(
                options.cloudBaseUrl(), options.cloudApiKey(), options.cloudModel());
        return switch (options.engine()) {
            case "local-whisper" -> local;
            case "cloud-asr" -> cloud;
            default -> new FallbackAudioEngine(cloud, local);
        };
    }

    private static AudioEngine createLocalEngine(Path whisperDir) {
        Path exe = whisperDir.resolve("Release").resolve("whisper-cli.exe");
        Path model = whisperDir.resolve("ggml-small.bin");
        if (Files.exists(exe) && Files.exists(model)) {
            return new WhisperEngine(exe, model, "auto");
        }
        return new AudioEngine() {
            @Override
            public String transcribe(byte[] wavData) {
                return "";
            }

            @Override
            public boolean isAvailable() {
                return false;
            }
        };
    }

    @Override
    public void run() {
        capturer = new AudioCapturer(chunkSeconds, vadThreshold, source);
        if (!capturer.isAvailable() || !engine.isAvailable()) {
            markError("Audio not available: source=" + capturer.source()
                    + " input=" + capturer.isAvailable()
                    + " engine=" + engine.isAvailable());
            log.warn("Audio not available: source={} input={} engine={}",
                    capturer.source(), capturer.isAvailable(), engine.isAvailable());
            return;
        }
        ensureBucket();
        log.info("音频采集已启动 (source={}, engine={})", capturer.source(), engine.name());

        while (running) {
            try {
                AudioCapturer.CaptureResult capture = capturer.captureResult();
                if (capture == null) {
                    markSample(null);
                    markError("Audio capture returned no data");
                    Thread.sleep(500);
                    continue;
                }
                markSample(capture);
                if (!capture.voiceDetected()) {
                    markSilent(capture);
                    Thread.sleep(500); // silent — short sleep
                    continue;
                }
                markVoice(capture);

                String text = engine.transcribe(capture.wavData());
                if (!engine.isAvailable()) {
                    markError("Audio transcription engine became unavailable");
                    log.warn("Audio transcription engine became unavailable");
                    break;
                }
                if (text.isEmpty()) {
                    markEmptyTranscript();
                    Thread.sleep(1000);
                    continue;
                }
                markTranscript();

                Map<String, Object> body = heartbeatBody(text, engine.name(), capture,
                        chunkSeconds, Instant.now());

                String json = MAPPER.writeValueAsString(body);
                HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(serverUrl + "/api/0/buckets/" + bucketId + "/heartbeat"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
                httpClient.send(req, HttpResponse.BodyHandlers.discarding());

                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                markError(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                log.error("Audio error", e);
            }
        }
    }

    public String status() {
        AudioCapturer current = capturer;
        return current != null && current.isAvailable() && engine.isAvailable() ? "running" : "degraded";
    }

    public AudioCaptureDiagnostics diagnostics() {
        AudioCapturer current = capturer;
        if (current == null) {
            return new AudioCaptureDiagnostics(
                    false,
                    engine.isAvailable(),
                    source,
                    engine.name(),
                    "",
                    AudioCapturer.availableInputDevices(),
                    vadThreshold,
                    sampleCount.get(),
                    silentCount.get(),
                    voiceCount.get(),
                    emptyTranscriptCount.get(),
                    transcriptCount.get(),
                    lastSampleAt,
                    lastSilentAt,
                    lastVoiceAt,
                    lastEmptyTranscriptAt,
                    lastTranscriptAt,
                    lastErrorAt,
                    lastError,
                    lastRms);
        }
        return new AudioCaptureDiagnostics(
                current.isAvailable(),
                engine.isAvailable(),
                current.source(),
                engine.name(),
                current.deviceName(),
                AudioCapturer.availableInputDevices(),
                vadThreshold,
                sampleCount.get(),
                silentCount.get(),
                voiceCount.get(),
                emptyTranscriptCount.get(),
                transcriptCount.get(),
                lastSampleAt,
                lastSilentAt,
                lastVoiceAt,
                lastEmptyTranscriptAt,
                lastTranscriptAt,
                lastErrorAt,
                lastError,
                lastRms);
    }

    public void shutdown() {
        running = false;
        interrupt();
        AudioCapturer current = capturer;
        if (current != null) {
            current.close();
        }
    }

    static Map<String, Object> heartbeatBody(
            String text,
            String engineName,
            AudioCapturer.CaptureResult capture,
            int chunkSeconds,
            Instant timestamp) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("text", text);
        data.put("engine", engineName);
        data.put("source", capture.source());
        data.put("device", capture.deviceName());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", timestamp.toString());
        body.put("duration", (double) chunkSeconds);
        body.put("data", data);
        return body;
    }

    private void ensureBucket() {
        try {
            Map<String, Object> bucket = Map.of(
                "id", bucketId, "name", "Audio watcher",
                "type", "audio", "client", "audio",
                "hostname", getHostname(),
                "created", Instant.now().toString());
            String json = MAPPER.writeValueAsString(bucket);
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(serverUrl + "/api/0/buckets/" + bucketId))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
            httpClient.send(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) {}
    }

    private void markSample(AudioCapturer.CaptureResult capture) {
        sampleCount.incrementAndGet();
        lastSampleAt = now();
        if (capture != null) {
            lastRms = capture.rms();
        }
    }

    private void markSilent(AudioCapturer.CaptureResult capture) {
        silentCount.incrementAndGet();
        lastSilentAt = lastSampleAt == null ? now() : lastSampleAt;
        if (capture != null) {
            lastRms = capture.rms();
        }
    }

    private void markVoice(AudioCapturer.CaptureResult capture) {
        voiceCount.incrementAndGet();
        lastVoiceAt = lastSampleAt == null ? now() : lastSampleAt;
        if (capture != null) {
            lastRms = capture.rms();
        }
    }

    private void markEmptyTranscript() {
        emptyTranscriptCount.incrementAndGet();
        lastEmptyTranscriptAt = now();
    }

    private void markTranscript() {
        transcriptCount.incrementAndGet();
        lastTranscriptAt = now();
    }

    private void markError(String message) {
        lastErrorAt = now();
        lastError = message;
    }

    private static String now() {
        return Instant.now().toString();
    }

    private static String getHostname() {
        try { return java.net.InetAddress.getLocalHost().getHostName(); }
        catch (Exception e) { return "unknown"; }
    }
}
