package com.selfanalyst.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfanalyst.config.Config;
import io.modelcontextprotocol.client.McpClient;
import com.selfanalyst.desktop.store.ChatSessionStore;
import com.selfanalyst.integrations.*;
import com.selfanalyst.integrations.policy.MailSessionPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class McpManagerDeleteTest {
    @TempDir Path root;

    private static IntegrationConfig.McpServer server(String id, String reference) {
        return new IntegrationConfig.McpServer(id, "Same display name", true, "streamable-http",
                "https://example.com/" + id, "bearer", reference, "session", IntegrationConfig.Budget.defaults());
    }

    private static IntegrationConfig.MailAccount account(String reference) {
        return new IntegrationConfig.MailAccount("mail", "qq", "12345@qq.com", "Synthetic mailbox", false,
                List.of("INBOX"), false, reference, "session", 0);
    }

    private static final class Credentials implements CredentialStore {
        final SessionCredentialStore delegate = new SessionCredentialStore();
        final List<String> deleted = new ArrayList<>();
        boolean failDelete;
        @Override public String write(CredentialTarget target, char[] secret, String mode) throws IOException {
            return delegate.write(target, secret, mode);
        }
        @Override public Optional<char[]> read(String reference, CredentialTarget target) throws IOException {
            return delegate.read(reference, target);
        }
        @Override public void delete(String reference) throws IOException {
            deleted.add(reference);
            if (failDelete) throw new IOException("synthetic vault failure");
            delegate.delete(reference);
        }
        @Override public void close() { delegate.close(); }
    }

    private IntegrationConfig fixture(IntegrationStore store, Credentials credentials) throws IOException {
        String one = credentials.write(server("one", null).credentialTarget(), "synthetic-one".toCharArray(), "session");
        String two = credentials.write(server("two", null).credentialTarget(), "synthetic-two".toCharArray(), "session");
        String mail = credentials.write(account(null).credentialTarget(), "synthetic-mail".toCharArray(), "session");
        return store.save(0, new IntegrationConfig(1, 0, List.of(server("one", one), server("two", two)),
                List.of(account(mail)), List.of(
                new IntegrationConfig.ToolApproval("one", "search", "a".repeat(64), 0),
                new IntegrationConfig.ToolApproval("two", "search", "b".repeat(64), 0),
                new IntegrationConfig.ToolApproval(IntegrationConfig.LEGACY_WEBSEARCH_ID, "search", "c".repeat(64), 0))));
    }

    @Test void deletionRemovesOnlyExactIdentityAndItsCredentialsAndApprovalsWithoutNetwork() throws Exception {
        var connections = new AtomicInteger();
        try (var store = new IntegrationStore(root); var credentials = new Credentials();
             var sessions = new ChatSessionStore(root.resolve("memory"));
             var manager = new McpManager(store, credentials, new MailSessionPolicy(sessions),
                     Config.testDefaults(root.resolve("memory")), new ApprovalRegistry(),
                     (endpoint, timeout, initialization, maximum, bearer) -> {
                         connections.incrementAndGet(); throw new AssertionError("deletion must not connect");
                     })) {
            var before = fixture(store, credentials);
            var removed = before.mcpServers().getFirst();
            var kept = before.mcpServers().get(1);
            var result = manager.delete(before.revision(), "mcp", "one");
            assertEquals(before.revision() + 1, result.revision());
            assertEquals(List.of(kept), result.mcpServers());
            assertEquals(before.mailAccounts(), result.mailAccounts());
            assertEquals(before.toolApprovals().subList(1, 3), result.toolApprovals());
            assertEquals(List.of(removed.credentialRef()), credentials.deleted);
            assertFalse(credentials.configured(removed.credentialRef(), removed.credentialTarget()));
            assertTrue(credentials.configured(kept.credentialRef(), kept.credentialTarget()));
            assertEquals(result, store.snapshot());
            assertEquals(0, connections.get());
        }
    }

    @Test void conflictWrongKindUnknownAndLegacyNeverChangeMetadataOrCredentials() throws Exception {
        try (var store = new IntegrationStore(root); var credentials = new Credentials();
             var sessions = new ChatSessionStore(root.resolve("memory"));
             var manager = new McpManager(store, credentials, new MailSessionPolicy(sessions),
                     Config.testDefaults(root.resolve("memory")), new PinnedHttpsClient(), new ApprovalRegistry())) {
            var before = fixture(store, credentials);
            assertEquals("revision_conflict", assertThrows(IOException.class,
                    () -> manager.delete(0, "mcp", "one")).getMessage());
            for (var pair : List.of(List.of("mail", "one"), List.of("mcp", "mail"),
                    List.of("mcp", "missing"), List.of("legacy", "one"), List.of("mcp", "legacy-websearch"))) {
                assertThrows(IllegalArgumentException.class, () -> manager.delete(1, pair.get(0), pair.get(1)));
                assertEquals(before, store.snapshot());
                assertTrue(credentials.deleted.isEmpty());
            }
        }
    }

    @Test void mailboxDeletionKeepsMcpAndDuplicateRequestCannotDeleteAnotherConnection() throws Exception {
        try (var store = new IntegrationStore(root); var credentials = new Credentials();
             var sessions = new ChatSessionStore(root.resolve("memory"));
             var manager = new McpManager(store, credentials, new MailSessionPolicy(sessions),
                     Config.testDefaults(root.resolve("memory")), new PinnedHttpsClient(), new ApprovalRegistry())) {
            var before = fixture(store, credentials);
            var result = manager.delete(1, "mail", "mail");
            assertTrue(result.mailAccounts().isEmpty());
            assertEquals(before.mcpServers(), result.mcpServers());
            assertEquals(before.toolApprovals(), result.toolApprovals());
            assertEquals(List.of(before.mailAccounts().getFirst().credentialRef()), credentials.deleted);
            assertThrows(IOException.class, () -> manager.delete(1, "mail", "mail"));
            assertThrows(IllegalArgumentException.class, () -> manager.delete(2, "mail", "mail"));
            assertEquals(result, store.snapshot());
        }
    }

    @Test void discoveredEmptyCatalogIsDistinctFromNeverDiscoveredWhileDisabled() throws Exception {
        var json = new ObjectMapper();
        McpManager.ClientFactory factory = (endpoint, timeout, initialization, maximum, bearer) -> McpClient.sync(
                new PinnedMcpTransport((url, method, headers, body, budget, limit) -> {
                    var request = json.readTree(body);
                    if (!request.has("id")) return new PinnedHttpsClient.Response(202, Map.of(), new byte[0]);
                    Object result = switch (request.path("method").asText()) {
                        case "initialize" -> Map.of("protocolVersion", "2025-03-26", "capabilities", Map.of("tools", Map.of()),
                                "serverInfo", Map.of("name", "synthetic-empty", "version", "1"));
                        case "tools/list" -> Map.of("tools", List.of());
                        default -> throw new AssertionError("Only discovery is permitted");
                    };
                    return new PinnedHttpsClient.Response(200, Map.of("content-type", List.of("application/json")),
                            json.writeValueAsBytes(Map.of("jsonrpc", "2.0", "id", json.convertValue(request.get("id"), Object.class), "result", result)));
                }, endpoint, timeout, maximum, bearer)).requestTimeout(timeout).initializationTimeout(initialization).build();
        try (var store = new IntegrationStore(root); var credentials = new Credentials();
             var sessions = new ChatSessionStore(root.resolve("memory"));
             var manager = new McpManager(store, credentials, new MailSessionPolicy(sessions),
                     Config.testDefaults(root.resolve("memory")), new ApprovalRegistry(), factory)) {
            var server = new IntegrationConfig.McpServer("empty", "Empty", false, "streamable-http",
                    "https://example.com/empty", "anonymous", null, "session", IntegrationConfig.Budget.defaults());
            manager.save(0, new IntegrationConfig(1, 0, List.of(server), List.of(), List.of()));
            assertEquals(false, ((Map<?, ?>) manager.statuses().get("empty")).get("catalogAvailable"));
            manager.discover("empty");
            var status = (Map<?, ?>) manager.statuses().get("empty");
            assertEquals(true, status.get("catalogAvailable"));
            assertEquals("disabled", status.get("state"));
            assertEquals(List.of(), status.get("tools"));
            var changed = new IntegrationConfig.McpServer("empty", "Empty", false, "streamable-http",
                    "https://example.com/changed", "anonymous", null, "session", IntegrationConfig.Budget.defaults());
            manager.save(1, new IntegrationConfig(1, 1, List.of(changed), List.of(), List.of()));
            assertEquals(false, ((Map<?, ?>) manager.statuses().get("empty")).get("catalogAvailable"));
        }
    }

    @Test void credentialCleanupFailureDoesNotRestoreConnectionAndCanBeRetried() throws Exception {
        try (var store = new IntegrationStore(root); var credentials = new Credentials();
             var sessions = new ChatSessionStore(root.resolve("memory"));
             var manager = new McpManager(store, credentials, new MailSessionPolicy(sessions),
                     Config.testDefaults(root.resolve("memory")), new PinnedHttpsClient(), new ApprovalRegistry())) {
            var before = fixture(store, credentials);
            credentials.failDelete = true;
            var result = manager.delete(1, "mcp", "one");
            assertEquals(List.of(before.mcpServers().get(1)), result.mcpServers());
            assertTrue(manager.credentialCleanupPending());
            assertFalse(manager.statuses().containsKey("one"));
            assertTrue(credentials.configured(before.mcpServers().getFirst().credentialRef(),
                    before.mcpServers().getFirst().credentialTarget()));
            credentials.failDelete = false;
            manager.credential(result.revision(), "two", "new-synthetic".toCharArray(), false);
            assertFalse(manager.credentialCleanupPending());
            assertFalse(credentials.configured(before.mcpServers().getFirst().credentialRef(),
                    before.mcpServers().getFirst().credentialTarget()));
        }
    }
}
