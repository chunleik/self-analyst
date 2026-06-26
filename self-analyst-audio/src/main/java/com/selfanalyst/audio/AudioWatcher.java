package com.selfanalyst.audio;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;

/**
 * Audio capture + transcription watcher.
 * Records 5s chunks → VAD → whisper.cpp transcription → push to AW.
 */
public class AudioWatcher extends Thread {

    private static final Logger log = LoggerFactory.getLogger(AudioWatcher.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final AudioCapturer capturer;
    private final AudioEngine engine;
    private final String serverUrl;
    private final String bucketId;
    private final HttpClient httpClient;
    private volatile boolean running = true;

    public AudioWatcher(String serverUrl) {
        this.serverUrl = serverUrl;
        this.capturer = new AudioCapturer(5, 0.01f);
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        this.bucketId = "aw-watcher-audio_" + getHostname();
        this.engine = createEngine();
        setDaemon(true);
        setName("audio-watcher");
    }

    private static AudioEngine createEngine() {
        java.nio.file.Path exe = java.nio.file.Path.of("tools/whisper/Release/whisper-cli.exe");
        java.nio.file.Path model = java.nio.file.Path.of("tools/whisper/ggml-small.bin");
        if (java.nio.file.Files.exists(exe) && java.nio.file.Files.exists(model)) {
            return new WhisperEngine(exe, model, "auto");
        }
        return wav -> "";
    }

    @Override
    public void run() {
        if (!capturer.isAvailable() || !engine.isAvailable()) {
            log.warn("Audio not available: mic={} engine={}",
                    capturer.isAvailable(), engine.isAvailable());
            return;
        }
        ensureBucket();
        log.info("音频采集已启动 ({})", engine.getClass().getSimpleName());

        while (running) {
            try {
                byte[] wav = capturer.capture();
                if (wav == null) {
                    Thread.sleep(500); // silent — short sleep
                    continue;
                }

                String text = engine.transcribe(wav);
                if (text.isEmpty()) {
                    Thread.sleep(1000);
                    continue;
                }

                Map<String, Object> data = new LinkedHashMap<>();
                data.put("text", text);
                data.put("engine", engine.getClass().getSimpleName());

                Map<String, Object> body = new LinkedHashMap<>();
                body.put("timestamp", Instant.now().toString());
                body.put("duration", 5.0);
                body.put("data", data);

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
                log.error("Audio error", e);
            }
        }
    }

    public void shutdown() {
        running = false;
        interrupt();
        capturer.close();
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

    private static String getHostname() {
        try { return java.net.InetAddress.getLocalHost().getHostName(); }
        catch (Exception e) { return "unknown"; }
    }
}
