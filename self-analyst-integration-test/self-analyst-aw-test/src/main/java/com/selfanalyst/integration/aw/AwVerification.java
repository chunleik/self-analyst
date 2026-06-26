package com.selfanalyst.integration.aw;

import com.selfanalyst.aw.AwServer;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public class AwVerification {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        Path dataDir = Files.createTempDirectory("aw-verify-");
        int port;
        try (ServerSocket ss = new ServerSocket(0)) { port = ss.getLocalPort(); }
        String base = "http://localhost:" + port + "/api/0";

        AwServer server = new AwServer(dataDir, port);
        server.start();

        try {
            step("create bucket and list it", () -> {
                String id = "v-bucket";
                httpPost(base + "/buckets/" + id, "{\"name\":\"V\",\"type\":\"test\",\"client\":\"aw-verify\",\"hostname\":\"h\"}");
                String body = httpGet(base + "/buckets/");
                check(body.contains(id), "list should contain " + id);
            });

            step("insert events and query count", () -> {
                String id = "v-events";
                httpPost(base + "/buckets/" + id, "{\"name\":\"V\",\"type\":\"test\",\"client\":\"aw-verify\",\"hostname\":\"h\"}");
                String ev = MAPPER.writeValueAsString(Map.of("timestamp", Instant.now().toString(), "duration", 25.5, "data", Map.of("app", "f")));
                httpPost(base + "/buckets/" + id + "/events", ev);
                String arr = MAPPER.writeValueAsString(List.of(
                        Map.of("timestamp", Instant.now().toString(), "duration", 10.0, "data", Map.of()),
                        Map.of("timestamp", Instant.now().toString(), "duration", 15.0, "data", Map.of())));
                httpPost(base + "/buckets/" + id + "/events", arr);
                List<?> events = MAPPER.readValue(httpGet(base + "/buckets/" + id + "/events?limit=10"), List.class);
                check(events.size() == 3, "expected 3 events, got " + events.size());
            });

            step("heartbeat merge within pulsetime", () -> {
                String id = "v-heartbeat";
                httpPost(base + "/buckets/" + id, "{\"name\":\"V\",\"type\":\"test\",\"client\":\"aw-verify\",\"hostname\":\"h\"}");
                Instant now = Instant.now();
                Map<String, Object> data = Map.of("app", "f", "title", "x");
                String hb1 = MAPPER.writeValueAsString(Map.of("timestamp", now.toString(), "duration", 10.0, "data", data));
                httpPost(base + "/buckets/" + id + "/heartbeat", hb1);
                String hb2 = MAPPER.writeValueAsString(Map.of("timestamp", now.plusSeconds(30).toString(), "duration", 10.0, "data", data));
                httpPost(base + "/buckets/" + id + "/heartbeat", hb2);
                List<?> events = MAPPER.readValue(httpGet(base + "/buckets/" + id + "/events?limit=10"), List.class);
                check(events.size() == 1, "heartbeat should merge into 1 event, got " + events.size());
            });

            step("query events with time range", () -> {
                String id = "v-time";
                httpPost(base + "/buckets/" + id, "{\"name\":\"V\",\"type\":\"test\",\"client\":\"aw-verify\",\"hostname\":\"h\"}");
                Instant start = Instant.parse("2026-06-10T10:00:00Z");
                String arr = MAPPER.writeValueAsString(List.of(
                        Map.of("timestamp", start.plusSeconds(100).toString(), "duration", 5.0, "data", Map.of()),
                        Map.of("timestamp", start.plusSeconds(3600).toString(), "duration", 10.0, "data", Map.of()),
                        Map.of("timestamp", start.plusSeconds(7100).toString(), "duration", 3.0, "data", Map.of())));
                httpPost(base + "/buckets/" + id + "/events", arr);
                String uri = base + "/buckets/" + id + "/events?limit=10&start=" + start + "&end=" + start.plusSeconds(3600);
                List<?> filtered = MAPPER.readValue(httpGet(uri), List.class);
                check(filtered.size() >= 1 && filtered.size() <= 2, "time range should filter, got " + filtered.size());
            });

            step("execute AQL query", () -> {
                String id = "v-aql";
                httpPost(base + "/buckets/" + id, "{\"name\":\"V\",\"type\":\"test\",\"client\":\"aw-verify\",\"hostname\":\"h\"}");
                Instant now = Instant.now();
                String arr = MAPPER.writeValueAsString(List.of(
                        Map.of("timestamp", now.toString(), "duration", 60.0, "data", Map.of("app", "a")),
                        Map.of("timestamp", now.plusSeconds(120).toString(), "duration", 30.0, "data", Map.of("app", "b"))));
                httpPost(base + "/buckets/" + id + "/events", arr);
                String tp = now.minusSeconds(60) + "/" + now.plusSeconds(300);
                String aql = MAPPER.writeValueAsString(Map.of("timeperiods", List.of(tp), "query", List.of(
                        "events = query_bucket(\"" + id + "\");",
                        "events = sort_by_duration(events);",
                        "RETURN = events;")));
                Map<?, ?> result = MAPPER.readValue(httpPost(base + "/query/", aql), Map.class);
                check(result.get("rows") != null, "AQL result should have rows");
                check(((List<?>) result.get("rows")).size() == 2, "should return 2 rows");
                check(result.get("total_duration") != null, "should have total_duration");
            });

        } finally {
            server.stop();
            deleteDir(dataDir);
        }

        System.out.println("\n=== AW Verification: " + passed + " passed, " + failed + " failed ===");
        System.exit(failed > 0 ? 1 : 0);
    }

    private static void step(String desc, Step r) {
        try {
            r.run();
            System.out.println("[PASS] " + desc);
            passed++;
        } catch (Throwable t) {
            System.out.println("[FAIL] " + desc + " — " + t.getMessage());
            failed++;
        }
    }

    private static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }

    private static String httpGet(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300)
            throw new RuntimeException("HTTP " + resp.statusCode() + " on GET " + url);
        return resp.body();
    }

    private static String httpPost(String url, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 400)
            throw new RuntimeException("HTTP " + resp.statusCode() + " on POST " + url);
        return resp.body();
    }

    private static void deleteDir(Path dir) {
        try {
            Files.walk(dir).sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                try { Files.delete(p); } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
    }

    @FunctionalInterface
    interface Step { void run() throws Exception; }
}
