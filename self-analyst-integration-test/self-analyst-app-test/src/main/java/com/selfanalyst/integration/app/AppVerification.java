package com.selfanalyst.integration.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfanalyst.aw.store.Database;
import com.selfanalyst.aw.store.EventStore;
import com.selfanalyst.aw.store.BucketStore;
import com.selfanalyst.aw.store.PulseTimeConfig;
import com.selfanalyst.config.Config;
import com.selfanalyst.desktop.DesktopServer;
import io.javalin.Javalin;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class AppVerification {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        Path tmpDir = Files.createTempDirectory("app-verify-");
        int port;
        try (ServerSocket ss = new ServerSocket(0)) { port = ss.getLocalPort(); }
        String base = "http://localhost:" + port;

        Path awDataDir = tmpDir.resolve("aw-data");

        Config config = Config.testDefaults(tmpDir);

        Database db = new Database(awDataDir);
        EventStore eventStore = new EventStore(db, PulseTimeConfig.DEFAULT);
        BucketStore bucketStore = new BucketStore(db);
        Javalin javalin = Javalin.create();

        DesktopServer desktop = new DesktopServer(javalin, config, null, eventStore,
                bucketStore, null, null, null, null);
        desktop.start();
        javalin.start(port);

        try {
            step("GET /desktop/status returns all sections", () -> {
                String body = httpGet(base + "/desktop/status");
                Map<?, ?> status = MAPPER.readValue(body, Map.class);
                check(status.containsKey("backend"), "should have backend");
                check(status.containsKey("aw"), "should have aw");
                check(status.containsKey("collectors"), "should have collectors");
                check(status.containsKey("llm"), "should have llm");
                Map<?, ?> collectors = (Map<?, ?>) status.get("collectors");
                check("disabled".equals(collectors.get("window")), "window collector should be disabled");
                check("disabled".equals(collectors.get("content")), "content collector should be disabled");
                check("disabled".equals(collectors.get("audio")), "audio collector should be disabled");
            });

            step("config GET/PUT round trip", () -> {
                String initialBody = httpGet(base + "/desktop/config");
                Map<?, ?> initial = MAPPER.readValue(initialBody, Map.class);
                Map<?, ?> llm = (Map<?, ?>) initial.get("llm");
                check(llm != null, "should have llm section");
                check(llm.get("model") != null, "should have model field");
                check(llm.get("apiKey") != null, "should have apiKey field");

                String putBody = MAPPER.writeValueAsString(Map.of("llm", Map.of("model", "verify-model")));
                String putResp = httpPut(base + "/desktop/config", putBody);
                Map<?, ?> putResult = MAPPER.readValue(putResp, Map.class);
                check(Boolean.TRUE.equals(putResult.get("saved")), "PUT should return saved=true");

                String afterBody = httpGet(base + "/desktop/config");
                Map<?, ?> after = MAPPER.readValue(afterBody, Map.class);
                Map<?, ?> llmAfter = (Map<?, ?>) after.get("llm");
                Map<?, ?> modelAfter = (Map<?, ?>) llmAfter.get("model");
                check("verify-model".equals(modelAfter.get("effectiveValue")), "model should be updated");
            });

            step("task CRUD lifecycle", () -> {
                String createBody = MAPPER.writeValueAsString(Map.of("title", "Verify Task", "priority", "high"));
                String createResp = httpPost(base + "/desktop/tasks", createBody);
                Map<?, ?> created = MAPPER.readValue(createResp, Map.class);
                String taskId = (String) created.get("id");
                check(taskId != null, "created task should have id");
                check("Verify Task".equals(created.get("title")), "title should match");

                // list
                List<?> tasks = MAPPER.readValue(httpGet(base + "/desktop/tasks"), List.class);
                check(tasks.stream().anyMatch(t -> taskId.equals(((Map<?, ?>) t).get("id"))), "task should be in list");

                // update
                String updBody = MAPPER.writeValueAsString(Map.of("title", "Updated"));
                Map<?, ?> updated = MAPPER.readValue(httpPut(base + "/desktop/tasks/" + taskId, updBody), Map.class);
                check("Updated".equals(updated.get("title")), "title should be updated");

                // complete
                httpPost(base + "/desktop/tasks/" + taskId + "/complete", "");
                // archive
                httpPost(base + "/desktop/tasks/" + taskId + "/archive", "");
                // delete
                httpDelete(base + "/desktop/tasks/" + taskId);

                List<?> tasksAfter = MAPPER.readValue(httpGet(base + "/desktop/tasks"), List.class);
                check(tasksAfter.stream().noneMatch(t -> taskId.equals(((Map<?, ?>) t).get("id"))), "task should be deleted");
            });

            step("chat without agent returns error", () -> {
                String chatBody = MAPPER.writeValueAsString(Map.of("message", "Hello?", "context", Map.of("type", "manual")));
                String resp = httpPost(base + "/desktop/chat", chatBody);
                Map<?, ?> result = MAPPER.readValue(resp, Map.class);
                String msg = (String) result.get("message");
                check(msg != null, "chat response should have message");
                check(msg.contains("LLM 未配置") || msg.contains("未配置"), "should indicate LLM not configured: " + msg);
            });

        } finally {
            javalin.stop();
            desktop.shutdown();
            db.close();
            deleteDir(tmpDir);
        }

        System.out.println("\n=== App Verification: " + passed + " passed, " + failed + " failed ===");
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
        check(resp.statusCode() >= 200 && resp.statusCode() < 300, "HTTP " + resp.statusCode());
        return resp.body();
    }

    private static String httpPost(String url, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(body.isEmpty() ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        check(resp.statusCode() >= 200 && resp.statusCode() < 400, "HTTP " + resp.statusCode());
        return resp.body();
    }

    private static String httpPut(String url, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        check(resp.statusCode() >= 200 && resp.statusCode() < 400, "HTTP " + resp.statusCode());
        return resp.body();
    }

    private static void httpDelete(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).DELETE().build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        check(resp.statusCode() >= 200 && resp.statusCode() < 400, "HTTP " + resp.statusCode());
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
