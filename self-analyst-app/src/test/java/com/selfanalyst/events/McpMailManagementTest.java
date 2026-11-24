package com.selfanalyst.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.selfanalyst.desktop.controller.DesktopMcpManagementController;
import com.selfanalyst.integrations.CredentialStore;
import com.selfanalyst.integrations.CredentialTarget;
import com.selfanalyst.integrations.IntegrationConfig;
import com.selfanalyst.mcp.LocalMcpHttpEndpoint;
import com.selfanalyst.mcp.McpMailManagement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class McpMailManagementTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DESKTOP = "synthetic-managed-token";
    @TempDir Path root;

    @Test
    void defaultsOffAndRotatingTokenDoesNotPublishAccountsOrWriteSecretsToDisk() throws Exception {
        var config = config();
        var vault = new TestVault();
        try (var manager = new McpMailManagement(root, config::get, vault, () -> true)) {
            assertFalse(manager.exposed("qq"));
            assertFalse(manager.exposed("all"));
            assertFalse(manager.authorize("qq", "x".repeat(43)));
            assertEquals(false, manager.status(12700).get("tokenConfigured"));
            String first = manager.rotate(0);
            assertTrue(manager.authorize("qq", first));
            assertFalse(manager.exposed("qq"));
            assertFalse(manager.exposed("all"));
            assertFalse(manager.status(12700).toString().contains(first));

            manager.save(1, List.of(new McpMailManagement.AccountSetting("qq", true, true, false)));
            assertEquals(List.of("list_folders", "search_emails", "create_draft"), toolNames(manager, "qq", first));
            assertEquals(List.of("qq__list_folders", "qq__search_emails", "qq__create_draft"),
                    toolNames(manager, "all", first));
            assertFalse(manager.exposed("other"));
            assertThrows(IOException.class, () -> manager.save(2,
                    List.of(new McpMailManagement.AccountSetting("qq", true, true, true))));

            char[] smtp = "synthetic-smtp-only".toCharArray();
            manager.smtp(2, "qq", smtp);
            assertArrayEquals(new char[smtp.length], smtp);
            assertFalse(toolNames(manager, "qq", first).contains("send_email"));
            manager.save(3, List.of(new McpMailManagement.AccountSetting("qq", true, true, true)));
            assertTrue(toolNames(manager, "qq", first).contains("send_email"));

            String second = manager.rotate(4);
            assertNotEquals(first, second);
            assertFalse(manager.authorize("qq", first));
            assertTrue(manager.authorize("qq", second));
            assertThrows(IOException.class, () -> manager.listTools("qq", first));
            String disk = Files.readString(root.resolve("config/local-mcp.json"));
            assertFalse(disk.contains(first));
            assertFalse(disk.contains(second));
            assertFalse(disk.contains("synthetic-smtp-only"));
        }
    }

    @Test
    void separatesDesktopManagementAndExternalMcpCredentials() throws Exception {
        var config = config();
        var server = new EventServer(root.resolve("events"), 0, DESKTOP);
        try (var manager = new McpMailManagement(root, config::get, new TestVault(), () -> true);
             var client = HttpClient.newHttpClient();
             AutoCloseable cleanup = server::stop) {
            var endpoint = new LocalMcpHttpEndpoint(manager);
            new DesktopMcpManagementController(manager).register(server.app());
            server.app().post(LocalMcpHttpEndpoint.PATH, endpoint::handle);
            server.start(0);
            var created = request(client, server.port(), "POST", "/desktop/mcp-management/token", null, DESKTOP,
                    "{\"revision\":0}");
            assertEquals(200, created.statusCode());
            String token = JSON.readTree(created.body()).path("token").asText();
            assertEquals(43, token.length());
            assertEquals(401, request(client, server.port(), "GET", "/desktop/mcp-management", token, null, "")
                    .statusCode());
            assertEquals(401, request(client, server.port(), "POST", "/mcp/qq", null, DESKTOP, ping()).statusCode());
            assertEquals(404, request(client, server.port(), "POST", "/mcp/qq", token, null, ping()).statusCode());
            assertEquals(200, request(client, server.port(), "PUT", "/desktop/mcp-management", null, DESKTOP, """
                    {"revision":1,"accounts":[{"id":"qq","expose":true,"allowDraft":false,"allowSend":false}]}
                    """).statusCode());
            assertEquals(200, request(client, server.port(), "POST", "/mcp/qq", token, null, ping()).statusCode());
        }

        var legacy = new EventServer(root.resolve("legacy"), 0, null);
        try (var manager = new McpMailManagement(root, config::get, new TestVault(), () -> true);
             var client = HttpClient.newHttpClient();
             AutoCloseable cleanup = legacy::stop) {
            new DesktopMcpManagementController(manager).register(legacy.app());
            legacy.start(0);
            assertEquals(403, request(client, legacy.port(), "GET", "/desktop/mcp-management", null, null, "")
                    .statusCode());
            assertEquals(403, request(client, legacy.port(), "POST", "/desktop/mcp-management/token",
                    null, null, "{\"revision\":2}").statusCode());
        }
    }

    @Test
    void refusesPersistentCredentialChangesWhenDeviceStorageIsUnavailable() throws Exception {
        var config = config();
        try (var manager = new McpMailManagement(root, config::get, new TestVault(), () -> false)) {
            assertEquals("persistent_credentials_unavailable",
                    assertThrows(IOException.class, () -> manager.rotate(0)).getMessage());
            char[] secret = "synthetic-smtp-only".toCharArray();
            assertEquals("persistent_credentials_unavailable",
                    assertThrows(IOException.class, () -> manager.smtp(0, "qq", secret)).getMessage());
            assertArrayEquals(new char[secret.length], secret);
            assertFalse(Files.exists(root.resolve("config/local-mcp.json")));
        }
    }

    @Test
    void rotationInvalidatesPendingDraftBeforeAnyMailboxOperation() throws Exception {
        var config = config();
        try (var manager = new McpMailManagement(root, config::get, new TestVault(), () -> true);
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            String token = manager.rotate(0);
            manager.save(1, List.of(new McpMailManagement.AccountSetting("qq", true, true, false)));
            ObjectNode args = JSON.createObjectNode().put("subject", "Synthetic").put("body", "No network");
            args.putArray("to").add("test@example.invalid");
            var draft = executor.submit(() -> manager.callTool("qq", token, "create_draft", args));
            String pending;
            try {
                pending = waitForApproval(manager);
                manager.rotate(2);
            } catch (Exception | AssertionError setupFailure) {
                draft.cancel(true);
                throw setupFailure;
            }
            var failure = assertThrows(ExecutionException.class, () -> draft.get(5, TimeUnit.SECONDS));
            assertEquals("approval_denied", failure.getCause().getMessage());
            assertFalse(manager.decide(pending, true));
        }
    }

    private static String waitForApproval(McpMailManagement manager) throws InterruptedException {
        long end = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < end) {
            var pending = (List<?>) manager.status(0).get("pending");
            if (!pending.isEmpty()) return (String) ((Map<?, ?>) pending.getFirst()).get("id");
            Thread.sleep(10);
        }
        throw new AssertionError("Synthetic draft did not reach the approval boundary");
    }

    private static AtomicReference<IntegrationConfig> config() {
        var enabled = new IntegrationConfig.MailAccount("qq", "qq", "synthetic@qq.com", "Synthetic",
                true, "all", List.of(), false, null, "session", 1);
        var other = new IntegrationConfig.MailAccount("other", "163", "synthetic@163.com", "Other",
                true, "all", List.of(), true, null, "session", 1);
        return new AtomicReference<>(new IntegrationConfig(1, 0, List.of(), List.of(enabled, other), List.of()));
    }

    private static List<String> toolNames(McpMailManagement manager, String route, String token) throws IOException {
        return manager.listTools(route, token).stream().map(row -> row.path("name")).map(JsonNode::asText).toList();
    }

    private static String ping() {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}";
    }

    private static HttpResponse<String> request(HttpClient client, int port, String method, String path,
                                                 String bearer, String desktop, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) request.header("Authorization", "Bearer " + bearer);
        if (desktop != null) request.header("X-SelfAnalyst-Token", desktop);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static final class TestVault implements CredentialStore {
        private record Entry(CredentialTarget target, char[] secret) { }
        private final Map<String, Entry> data = new ConcurrentHashMap<>();

        @Override
        public String write(CredentialTarget target, char[] secret, String storageMode) {
            assertEquals("windows", storageMode);
            String reference = "windows:" + UUID.randomUUID();
            data.put(reference, new Entry(target, secret.clone()));
            return reference;
        }

        @Override
        public Optional<char[]> read(String reference, CredentialTarget target) {
            Entry entry = reference == null ? null : data.get(reference);
            return entry != null && target.equals(entry.target())
                    ? Optional.of(entry.secret().clone()) : Optional.empty();
        }

        @Override
        public void delete(String reference) {
            var entry = data.remove(reference);
            if (entry != null) Arrays.fill(entry.secret(), '\0');
        }

        @Override
        public void close() {
            for (String reference : List.copyOf(data.keySet())) delete(reference);
        }
    }
}
