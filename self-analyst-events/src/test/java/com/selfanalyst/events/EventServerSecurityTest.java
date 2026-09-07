package com.selfanalyst.events;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class EventServerSecurityTest {

    @Test
    void rawEventPathFailsClosedWithoutManagedToken(@TempDir Path dataDir) throws Exception {
        int port = freePort();
        EventServer server = new EventServer(dataDir, port, null);
        server.start();
        try {
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(
                                    "http://127.0.0.1:" + port + "/desktop/raw-events"))
                            .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(503, response.statusCode());
            assertFalse(response.body().contains("bucket"));
        } finally {
            server.stop();
        }
    }

    @Test
    void rejectsUntrustedOriginsHostsAndMissingDesktopCredential(@TempDir Path dataDir) throws Exception {
        int port = freePort();
        EventServer server = new EventServer(dataDir, port, "launch-secret");
        server.start();
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> evilOrigin = client.send(HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + port + "/0/info"))
                    .header("Origin", "http://localhost.evil.example")
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(403, evilOrigin.statusCode());

            HttpResponse<String> allowedOrigin = client.send(HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + port + "/0/info"))
                    .header("Origin", "http://localhost:5700")
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, allowedOrigin.statusCode());
            assertEquals("http://localhost:5700",
                    allowedOrigin.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());

            HttpResponse<String> noCredential = client.send(HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + port + "/desktop/not-a-route"))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(401, noCredential.statusCode());

            HttpResponse<String> credentialAccepted = client.send(HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + port + "/desktop/not-a-route"))
                    .header("X-SelfAnalyst-Token", "launch-secret")
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertFalse(credentialAccepted.statusCode() == 401);

            String rawUrl = "http://127.0.0.1:" + port
                    + "/desktop/raw-events?bucketId=secret-bucket";
            assertEquals(401, client.send(HttpRequest.newBuilder(URI.create(rawUrl))
                    .GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(401, client.send(HttpRequest.newBuilder(
                            URI.create(rawUrl + "&token=launch-secret"))
                    .GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(401, client.send(HttpRequest.newBuilder(URI.create(rawUrl))
                    .header("X-SelfAnalyst-Token", "wrong")
                    .GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(400, client.send(HttpRequest.newBuilder(URI.create(rawUrl))
                    .header("X-SelfAnalyst-Token", "launch-secret")
                    .GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(400, client.send(HttpRequest.newBuilder(URI.create(rawUrl))
                    .header("Cookie", "self_analyst_session=launch-secret")
                    .GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());

            assertEquals(403, statusWithRawHost(port, "attacker.example:" + port));
        } finally {
            server.stop();
        }
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static int statusWithRawHost(int port, String host) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port);
             OutputStreamWriter writer = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))) {
            writer.write("GET /0/info HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n\r\n");
            writer.flush();
            String statusLine = reader.readLine();
            return Integer.parseInt(statusLine.split(" ")[1]);
        }
    }
}
