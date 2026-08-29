package com.selfanalyst.aw;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfanalyst.aw.model.Bucket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AwServerBucketVisibilityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void hiddenContentBucketCanBeIncludedExplicitly(@TempDir Path dataDir) throws Exception {
        int port = freePort();
        AwServer server = new AwServer(dataDir, port, null);
        server.bucketStore().create(Bucket.create(
                "aw-watcher-content_test-host",
                "content-watcher bucket",
                "listening",
                "aw-watcher-content",
                "test-host"));
        server.bucketStore().create(Bucket.create(
                "aw-watcher-window_test-host",
                "window-watcher bucket",
                "currentwindow",
                "aw-watcher-window",
                "test-host"));
        server.start();
        try {
            HttpClient client = HttpClient.newHttpClient();

            Map<String, Object> visible = get(client, port, "/api/0/buckets/");
            assertTrue(visible.containsKey("aw-watcher-window_test-host"));
            assertFalse(visible.containsKey("aw-watcher-content_test-host"));

            Map<String, Object> complete = get(
                    client, port, "/api/0/buckets/?include_hidden=true");
            assertTrue(complete.containsKey("aw-watcher-window_test-host"));
            assertTrue(complete.containsKey("aw-watcher-content_test-host"));
        } finally {
            server.stop();
        }
    }

    private static Map<String, Object> get(
            HttpClient client, int port, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + path))
                .GET()
                .build();
        HttpResponse<String> response = client.send(
                request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        return MAPPER.readValue(response.body(), new TypeReference<>() {});
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
