package com.selfanalyst.audio;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudAudioEngineTest {

    @Test
    void postsMultipartTranscriptionRequestAndParsesJsonText() throws Exception {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<byte[]> requestBody = new AtomicReference<>();

        HttpServer server = startServer((exchange) -> {
            path.set(exchange.getRequestURI().getPath());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            requestBody.set(exchange.getRequestBody().readAllBytes());
            byte[] response = "{\"text\":\"你好，世界\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            CloudAudioEngine engine = new CloudAudioEngine(baseUrl, "test-key", "gpt-4o-transcribe");

            String text = engine.transcribe(testWav());

            assertEquals("你好，世界", text);
            assertEquals("/v1/audio/transcriptions", path.get());
            assertEquals("Bearer test-key", authorization.get());
            assertTrue(contentType.get().startsWith("multipart/form-data; boundary="));
            String body = new String(requestBody.get(), StandardCharsets.ISO_8859_1);
            assertTrue(body.contains("name=\"model\""));
            assertTrue(body.contains("gpt-4o-transcribe"));
            assertTrue(body.contains("name=\"response_format\""));
            assertTrue(body.contains("json"));
            assertTrue(body.contains("name=\"file\"; filename=\"audio.wav\""));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void acceptsPlainTextTranscriptionResponses() throws Exception {
        HttpServer server = startServer((exchange) -> {
            byte[] response = "plain transcript".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            CloudAudioEngine engine = new CloudAudioEngine(baseUrl, "test-key", "whisper-1");

            assertEquals("plain transcript", engine.transcribe(testWav()));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void isUnavailableWithoutCredentialsOrModel() {
        assertFalse(new CloudAudioEngine("http://127.0.0.1:1/v1", "", "gpt-4o-transcribe").isAvailable());
        assertFalse(new CloudAudioEngine("http://127.0.0.1:1/v1", "key", "").isAvailable());
        assertFalse(new CloudAudioEngine("", "key", "gpt-4o-transcribe").isAvailable());
    }

    private static HttpServer startServer(ThrowingHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/audio/transcriptions", exchange -> {
            try {
                handler.handle(exchange);
            } catch (Exception e) {
                byte[] response = e.getMessage().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(500, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
            }
        });
        server.start();
        return server;
    }

    private static byte[] testWav() {
        byte[] wav = new byte[64];
        wav[0] = 'R';
        wav[1] = 'I';
        wav[2] = 'F';
        wav[3] = 'F';
        wav[8] = 'W';
        wav[9] = 'A';
        wav[10] = 'V';
        wav[11] = 'E';
        return wav;
    }

    @FunctionalInterface
    private interface ThrowingHandler {
        void handle(com.sun.net.httpserver.HttpExchange exchange) throws Exception;
    }
}
