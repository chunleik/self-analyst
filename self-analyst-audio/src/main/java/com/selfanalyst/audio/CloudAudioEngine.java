package com.selfanalyst.audio;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * OpenAI-compatible speech-to-text engine.
 */
public class CloudAudioEngine implements AudioEngine {

    private static final Logger log = LoggerFactory.getLogger(CloudAudioEngine.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final HttpClient client;
    private final Duration timeout;

    public CloudAudioEngine(String baseUrl, String apiKey, String model) {
        this(baseUrl, apiKey, model, Duration.ofSeconds(60));
    }

    public CloudAudioEngine(String baseUrl, String apiKey, String model, Duration timeout) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model == null ? "" : model.trim();
        this.timeout = timeout == null ? Duration.ofSeconds(60) : timeout;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public boolean isAvailable() {
        return !baseUrl.isBlank() && !apiKey.isBlank() && !model.isBlank();
    }

    @Override
    public String name() {
        return "CloudAudioEngine";
    }

    @Override
    public String transcribe(byte[] wavData) {
        if (!isAvailable() || wavData == null || wavData.length < 44) return "";
        try {
            String boundary = "selfanalyst-" + UUID.randomUUID();
            byte[] body = multipartBody(boundary, wavData);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/audio/transcriptions"))
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("Cloud ASR returned HTTP {}: {}", response.statusCode(), truncate(response.body()));
                return "";
            }
            return extractTranscript(response.body());
        } catch (Exception e) {
            log.warn("Cloud ASR request failed: {}", e.getMessage());
            return "";
        }
    }

    private byte[] multipartBody(String boundary, byte[] wavData) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(wavData.length + 1024);
        writeField(out, boundary, "model", model);
        writeField(out, boundary, "response_format", "json");
        write(out, "--" + boundary + "\r\n");
        write(out, "Content-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n");
        write(out, "Content-Type: audio/wav\r\n\r\n");
        out.write(wavData);
        write(out, "\r\n--" + boundary + "--\r\n");
        return out.toByteArray();
    }

    private static void writeField(ByteArrayOutputStream out, String boundary,
                                   String name, String value) throws Exception {
        write(out, "--" + boundary + "\r\n");
        write(out, "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n");
        write(out, value);
        write(out, "\r\n");
    }

    private static void write(ByteArrayOutputStream out, String value) throws Exception {
        out.write(value.getBytes(StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    static String extractTranscript(String body) {
        if (body == null || body.isBlank()) return "";
        String trimmed = body.trim();
        if (!trimmed.startsWith("{")) return trimmed;
        try {
            Map<String, Object> root = MAPPER.readValue(trimmed, Map.class);
            Object text = root.get("text");
            return text == null ? "" : String.valueOf(text).trim();
        } catch (Exception e) {
            return trimmed;
        }
    }

    private static String truncate(String body) {
        if (body == null) return "";
        String compact = body.replaceAll("\\s+", " ").trim();
        return compact.length() <= 300 ? compact : compact.substring(0, 300) + "...";
    }
}
