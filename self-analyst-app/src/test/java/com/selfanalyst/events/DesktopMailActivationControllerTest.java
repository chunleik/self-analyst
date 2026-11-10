package com.selfanalyst.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfanalyst.config.Config;
import com.selfanalyst.desktop.controller.DesktopIntegrationController;
import com.selfanalyst.desktop.store.ChatSessionStore;
import com.selfanalyst.integrations.*;
import com.selfanalyst.integrations.policy.MailSessionPolicy;
import com.selfanalyst.mcp.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class DesktopMailActivationControllerTest {
    @TempDir Path root;
    private static final String TOKEN = "synthetic-integration-token", MODEL = "https://model.example.com/v1";
    private static final String REQUEST = "{\"id\":\"mail-one\",\"expectedRevision\":1,\"disclosureVersion\":1,\"modelTarget\":\"" + MODEL + "\",\"secret\":\"synthetic-private-code\"}";
    private static IntegrationConfig initial() {
        var account = new IntegrationConfig.MailAccount("mail-one", "qq", "synthetic@qq.com", "Synthetic", false,
                "all", List.of("INBOX"), false, null, "session", 0);
        return new IntegrationConfig(1, 0, List.of(), List.of(account), List.of());
    }
    private static McpManager manager(Path root, IntegrationStore store, CredentialStore credentials, ChatSessionStore sessions) {
        return new McpManager(store, credentials, new MailSessionPolicy(sessions), Config.testDefaults(root.resolve("memory")),
                new PinnedHttpsClient(), new ApprovalRegistry());
    }
    @Test void strictActivationRejectsMissingNullWrongDuplicateTrailingOrForgedFieldsBeforeVaultWrites() throws Exception {
        var server = new EventServer(root.resolve("events"), 0, TOKEN);
        try (AutoCloseable cleanup = server::stop; var store = new IntegrationStore(root); var credentials = new TestSecureCredentialStore();
             var sessions = new ChatSessionStore(root.resolve("memory")); var manager = manager(root, store, credentials, sessions)) {
            store.save(0, initial());
            new DesktopIntegrationController(manager, () -> MODEL).register(server.app()); server.start(0);
            assertEquals(401, request(server, "/mail/activate", REQUEST, null).statusCode());
            for (String invalid : List.of(
                    REQUEST.replace(",\"disclosureVersion\":1", ""), REQUEST.replace("\"disclosureVersion\":1", "\"disclosureVersion\":0"),
                    REQUEST.replace("\"disclosureVersion\":1", "\"disclosureVersion\":null"), REQUEST.replace("\"disclosureVersion\":1", "\"disclosureVersion\":\"1\""),
                    REQUEST.replace("\"disclosureVersion\":1", "\"disclosureVersion\":1.0"), REQUEST.replace("\"disclosureVersion\":1", "\"disclosureVersion\":4294967297"),
                    REQUEST.replace("\"disclosureVersion\":1", "\"disclosureVersion\":0,\"disclosureVersion\":1"),
                    REQUEST.replace("\"expectedRevision\":1", "\"expectedRevision\":1,\"expectedRevision\":1"),
                    REQUEST.replace("\"expectedRevision\":1", "\"expectedRevision\":\"1\""),
                    REQUEST.replace("\"modelTarget\":\"" + MODEL + "\"", "\"modelTarget\":null"),
                    REQUEST.replace(",\"modelTarget\":\"" + MODEL + "\"", ""), REQUEST.replace(MODEL, ""),
                    REQUEST.replace("\"secret\":\"synthetic-private-code\"", "\"secret\":null"),
                    REQUEST.replace("\"secret\":\"synthetic-private-code\"", "\"secret\":\"\""),
                    REQUEST.replace("\"id\":\"mail-one\"", "\"id\":\"mail-one\",\"persist\":false"),
                    REQUEST + " {}", REQUEST + " null")) {
                var response = request(server, "/mail/activate", invalid, TOKEN);
                assertEquals(400, response.statusCode(), invalid); assertFalse(response.body().contains("synthetic-private-code"));
                assertEquals(0, credentials.writes); assertEquals(1, store.snapshot().revision());
                assertFalse(store.snapshot().mailAccounts().getFirst().enabled());
            }
            // No body/secret submission is how cancellation remains side-effect free; a normal GET cannot activate.
            assertEquals(0, credentials.writes);
            for (String kind : List.of("mail", "mcp")) for (boolean persist : List.of(false, true)) {
                var response = request(server, "/credential", "{\"kind\":\"" + kind + "\",\"id\":\"mail-one\",\"secret\":\"synthetic-private-code\",\"persist\":" + persist + ",\"expectedRevision\":1}", TOKEN);
                assertEquals(400, response.statusCode()); assertTrue(response.body().contains("mail_activation_required"));
                assertEquals(0, credentials.writes);
            }
        }
    }
    @Test void activationReturnsOnlySavedEnabledStateAndRejectsRepeatedRevisionAndChangedModelTarget() throws Exception {
        var server = new EventServer(root.resolve("events"), 0, TOKEN); var target = new AtomicReference<>(MODEL);
        try (AutoCloseable cleanup = server::stop; var store = new IntegrationStore(root); var credentials = new TestSecureCredentialStore();
             var sessions = new ChatSessionStore(root.resolve("memory")); var manager = manager(root, store, credentials, sessions)) {
            store.save(0, initial()); new DesktopIntegrationController(manager, target::get).register(server.app()); server.start(0);
            target.set(null);
            var missing = request(server, "/mail/activate", REQUEST, TOKEN);
            assertEquals(400, missing.statusCode()); assertTrue(missing.body().contains("model_target_unavailable")); assertEquals(0, credentials.writes);
            target.set(MODEL + "/changed");
            var changed = request(server, "/mail/activate", REQUEST, TOKEN);
            assertEquals(409, changed.statusCode()); assertTrue(changed.body().contains("model_target_changed")); assertEquals(0, credentials.writes);
            target.set(MODEL);
            var response = request(server, "/mail/activate", REQUEST, TOKEN);
            assertEquals(200, response.statusCode()); assertFalse(response.body().contains("synthetic-private-code"));
            var json = new ObjectMapper().readTree(response.body());
            assertEquals(2, json.path("config").path("revision").asInt());
            assertTrue(json.path("config").path("mailAccounts").get(0).path("enabled").asBoolean());
            assertEquals("windows", json.path("config").path("mailAccounts").get(0).path("storageMode").asText());
            assertEquals("not_connected", json.path("statuses").path("mail-one").path("state").asText());
            assertEquals(409, request(server, "/mail/activate", REQUEST, TOKEN).statusCode()); assertEquals(1, credentials.writes);
            String reuse = REQUEST.replace("\"expectedRevision\":1", "\"expectedRevision\":2").replace(",\"secret\":\"synthetic-private-code\"", "");
            assertEquals(200, request(server, "/mail/activate", reuse, TOKEN).statusCode()); assertEquals(1, credentials.writes);
        }
    }
    @Test void safeErrorsDistinguishUnsupportedVaultAndExposeOrphanCleanupWarning() throws Exception {
        var server = new EventServer(root.resolve("events"), 0, TOKEN);
        try (AutoCloseable cleanup = server::stop; var store = new IntegrationStore(root); var credentials = new TestSecureCredentialStore();
             var sessions = new ChatSessionStore(root.resolve("memory")); var manager = manager(root, store, credentials, sessions)) {
            store.save(0, initial()); new DesktopIntegrationController(manager, () -> MODEL).register(server.app()); server.start(0);
            credentials.writeFailure = "persistent_credentials_unavailable";
            var unavailable = request(server, "/mail/activate", REQUEST, TOKEN);
            assertEquals(400, unavailable.statusCode()); assertTrue(unavailable.body().contains("persistent_credentials_unavailable"));
            credentials.writeFailure = null; credentials.wrongRead = true; credentials.failDelete = true;
            var failed = request(server, "/mail/activate", REQUEST, TOKEN);
            assertEquals(400, failed.statusCode()); assertTrue(failed.body().contains("credential_verification_failed"));
            assertTrue(new ObjectMapper().readTree(failed.body()).path("credentialCleanupPending").asBoolean());
            assertFalse(failed.body().contains("synthetic-private-code")); assertFalse(store.snapshot().mailAccounts().getFirst().enabled());
            credentials.failDelete = false;
        }
    }
    @Test void activationIsNotAvailableThroughUnmanagedLocalhost() throws Exception {
        var server = new EventServer(root.resolve("events"), 0, null);
        try (AutoCloseable cleanup = server::stop; var store = new IntegrationStore(root); var credentials = new TestSecureCredentialStore();
             var sessions = new ChatSessionStore(root.resolve("memory")); var manager = manager(root, store, credentials, sessions)) {
            store.save(0, initial()); new DesktopIntegrationController(manager, () -> MODEL).register(server.app()); server.start(0);
            assertEquals(403, request(server, "/mail/activate", REQUEST, null).statusCode()); assertEquals(0, credentials.writes);
        }
    }
    @Test void managementSnapshotWaitsForActivationAndReturnsOneCommittedRevision() throws Exception {
        var server = new EventServer(root.resolve("events"), 0, TOKEN);
        var writeStarted = new CountDownLatch(1);
        var releaseWrite = new CountDownLatch(1);
        var getStarted = new CountDownLatch(1);
        var getThread = new AtomicReference<Thread>();
        try (AutoCloseable cleanup = server::stop; var store = new IntegrationStore(root); var credentials = new TestSecureCredentialStore();
             var sessions = new ChatSessionStore(root.resolve("memory")); var manager = manager(root, store, credentials, sessions)) {
            store.save(0, initial());
            credentials.afterWrite = () -> {
                writeStarted.countDown();
                try {
                    if (!releaseWrite.await(10, TimeUnit.SECONDS)) throw new java.io.IOException("synthetic_write_timeout");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new java.io.IOException("synthetic_write_interrupted");
                }
            };
            server.app().before("/desktop/integrations", ctx -> {
                if (ctx.method() == io.javalin.http.HandlerType.GET) {
                    getThread.set(Thread.currentThread()); getStarted.countDown();
                }
            });
            new DesktopIntegrationController(manager, () -> MODEL).register(server.app()); server.start(0);
            var client = HttpClient.newHttpClient();
            String endpoint = "http://127.0.0.1:" + server.port() + "/desktop/integrations";
            try {
                var post = client.sendAsync(HttpRequest.newBuilder(URI.create(endpoint + "/mail/activate"))
                        .header("X-SelfAnalyst-Token", TOKEN).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(REQUEST)).build(), HttpResponse.BodyHandlers.ofString());
                assertTrue(writeStarted.await(5, TimeUnit.SECONDS), "Activation must reach the controlled vault write");
                var get = client.sendAsync(HttpRequest.newBuilder(URI.create(endpoint)).header("X-SelfAnalyst-Token", TOKEN)
                        .GET().build(), HttpResponse.BodyHandlers.ofString());
                assertTrue(getStarted.await(5, TimeUnit.SECONDS), "GET must enter the management route while activation is paused");
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                boolean blockedInSnapshot = false;
                while (System.nanoTime() < deadline) {
                    Thread thread = getThread.get();
                    blockedInSnapshot = thread.getState() == Thread.State.BLOCKED && Arrays.stream(thread.getStackTrace())
                            .anyMatch(frame -> frame.getClassName().equals(DesktopIntegrationController.class.getName())
                                    && frame.getMethodName().equals("snapshot"));
                    if (blockedInSnapshot) break;
                    Thread.sleep(5);
                }
                assertTrue(blockedInSnapshot, "GET must wait for the activation before completing its snapshot");
                assertFalse(get.isDone());
                releaseWrite.countDown();
                var posted = post.get(5, TimeUnit.SECONDS); var fetched = get.get(5, TimeUnit.SECONDS);
                assertEquals(200, posted.statusCode()); assertEquals(200, fetched.statusCode());
                var json = new ObjectMapper().readTree(fetched.body());
                assertEquals(store.snapshot().revision(), json.path("config").path("revision").asLong());
                assertEquals(2, json.path("config").path("revision").asLong());
                var account = json.path("config").path("mailAccounts").get(0);
                assertTrue(account.path("enabled").asBoolean()); assertEquals("windows", account.path("storageMode").asText());
                var status = json.path("statuses").path("mail-one");
                assertTrue(status.path("credentialConfigured").asBoolean()); assertEquals("not_connected", status.path("state").asText());
                assertFalse(json.path("credentialCleanupPending").asBoolean());
                assertFalse(fetched.body().contains("synthetic-private-code"));
            } finally { releaseWrite.countDown(); }
        } finally { releaseWrite.countDown(); }
    }
    private static HttpResponse<String> request(EventServer server, String suffix, String body, String token) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/desktop/integrations" + suffix))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) builder.header("X-SelfAnalyst-Token", token);
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
