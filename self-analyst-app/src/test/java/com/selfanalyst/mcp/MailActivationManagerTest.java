package com.selfanalyst.mcp;

import com.selfanalyst.config.Config;
import com.selfanalyst.desktop.store.ChatSessionStore;
import com.selfanalyst.integrations.*;
import com.selfanalyst.integrations.policy.MailSessionPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class MailActivationManagerTest {
    @TempDir Path root;
    private static IntegrationConfig.MailAccount account(boolean enabled, int disclosure, String scope, List<String> folders,
                                                          boolean body, String email, String provider, String name) {
        return new IntegrationConfig.MailAccount("mail-one", provider, email, name, enabled, scope, folders, body, null, "session", disclosure);
    }
    private static IntegrationConfig.MailAccount fresh() {
        return account(false, 0, "all", List.of("INBOX"), false, "synthetic@qq.com", "qq", "Synthetic");
    }
    private static IntegrationConfig config(long revision, IntegrationConfig.MailAccount account) {
        return new IntegrationConfig(1, revision, List.of(), List.of(account), List.of());
    }
    private McpManager manager(IntegrationStore store, CredentialStore credentials, ChatSessionStore sessions, AtomicInteger calls) {
        return new McpManager(store, credentials, new MailSessionPolicy(sessions), Config.testDefaults(root.resolve("memory")),
                new ApprovalRegistry(), (endpoint, timeout, initialization, maximum, bearer) -> {
                    calls.incrementAndGet(); throw new AssertionError("No external connection during metadata save or activation");
                });
    }
    @Test void metadataCannotForgeEnabledOrDisclosureAndLegacyCredentialEndpointCannotBypassActivation() throws Exception {
        try (var store = new IntegrationStore(root); var credentials = new TestSecureCredentialStore();
             var sessions = ChatSessionStore.openExclusive(root.resolve("memory")); var manager = manager(store, credentials, sessions, new AtomicInteger())) {
            for (var forged : List.of(account(true, 1, "all", List.of(), false, "synthetic@qq.com", "qq", "Synthetic"),
                    account(false, 1, "all", List.of(), false, "synthetic@qq.com", "qq", "Synthetic"))) {
                assertEquals("mail_metadata_authorization_immutable", assertThrows(IOException.class, () -> manager.save(0, config(0, forged))).getMessage());
                assertEquals(0, manager.config().revision());
            }
            manager.save(0, config(0, fresh()));
            for (boolean persist : List.of(false, true)) {
                char[] secret = "synthetic-bypass".toCharArray();
                assertEquals("mail_activation_required", assertThrows(IOException.class,
                        () -> manager.credential(1, "mail-one", secret, persist)).getMessage());
                assertArrayEquals(new char[secret.length], secret);
            }
            assertEquals(0, credentials.writes); assertEquals(1, manager.config().revision());
        }
    }
    @Test void activationWipesInputAndDoesNotClaimConnectionTestOrGrantSessionModelConsent() throws Exception {
        var connections = new AtomicInteger();
        try (var store = new IntegrationStore(root); var credentials = new TestSecureCredentialStore();
             var sessions = ChatSessionStore.openExclusive(root.resolve("memory"))) {
            var policy = new MailSessionPolicy(sessions);
            try (var manager = new McpManager(store, credentials, policy, Config.testDefaults(root.resolve("memory")),
                    new ApprovalRegistry(), (endpoint, timeout, initialization, maximum, bearer) -> {
                        connections.incrementAndGet(); throw new AssertionError("No network");
                    })) {
                manager.save(0, config(0, fresh()));
                char[] secret = "synthetic-code".toCharArray();
                manager.activateMail(1, "mail-one", 1, "model", secret, () -> "model");
                assertArrayEquals(new char[secret.length], secret); assertEquals(0, connections.get());
                var status = (Map<?, ?>) manager.statuses().get("mail-one");
                assertEquals("not_connected", status.get("state")); assertEquals(true, status.get("credentialConfigured"));
                assertFalse(status.toString().contains("synthetic-code"));
                var session = sessions.create(new ChatSessionStore.CreateRequest());
                assertFalse(policy.containsMail(session.id));
                // Activation does not replace the independent recipient gate used before actual mail/model use.
                assertThrows(MailSessionPolicy.ModelConsentRequiredException.class,
                        () -> policy.beforeMailResult(session.id, "https://model.example.com/v1"));
                policy.consentTarget(session.id, "https://model.example.com/v1");
                policy.beforeMailResult(session.id, "https://model.example.com/v1");
                assertThrows(MailSessionPolicy.ModelConsentRequiredException.class,
                        () -> policy.requireModelTarget(session.id, "https://changed.example.com/v1"));
            }
        }
    }
    @Test void everyPermissionChangeDisablesAndResetsDisclosureButDisplayNameRetainsActivation() throws Exception {
        List<IntegrationConfig.MailAccount> edits = List.of(
                account(true, 1, "selected", List.of("INBOX"), false, "synthetic@qq.com", "qq", "Synthetic"),
                account(true, 1, "all", List.of("INBOX", "Archive"), false, "synthetic@qq.com", "qq", "Synthetic"),
                account(true, 1, "all", List.of("INBOX"), true, "synthetic@qq.com", "qq", "Synthetic"),
                account(true, 1, "all", List.of("INBOX"), false, "changed@qq.com", "qq", "Synthetic"),
                account(true, 1, "all", List.of("INBOX"), false, "synthetic@163.com", "163", "Synthetic"));
        for (int i = 0; i < edits.size(); i++) {
            Path scenario = root.resolve("edit-" + i);
            try (var store = new IntegrationStore(scenario); var credentials = new TestSecureCredentialStore();
                 var sessions = ChatSessionStore.openExclusive(scenario.resolve("memory")); var manager = manager(store, credentials, sessions, new AtomicInteger())) {
                manager.save(0, config(0, fresh()));
                var active = manager.activateMail(1, "mail-one", 1, "model", "code".toCharArray(), () -> "model").mailAccounts().getFirst();
                var renamed = account(true, 1, "all", List.of("INBOX"), false, "synthetic@qq.com", "qq", "New name");
                var kept = manager.save(2, config(2, renamed)).mailAccounts().getFirst();
                assertTrue(kept.enabled()); assertEquals(1, kept.disclosureVersion()); assertEquals(active.credentialRef(), kept.credentialRef());
                assertEquals("mail_metadata_authorization_immutable", assertThrows(IOException.class,
                        () -> manager.save(3, config(3, fresh()))).getMessage());
                var edit = edits.get(i); var saved = manager.save(3, config(3, edit)).mailAccounts().getFirst();
                assertFalse(saved.enabled()); assertEquals(0, saved.disclosureVersion());
                if (edit.credentialTarget().equals(active.credentialTarget())) {
                    assertEquals(active.credentialRef(), saved.credentialRef()); assertEquals("windows", saved.storageMode());
                    var reactivated = manager.activateMail(4, "mail-one", 1, "model", null, () -> "model").mailAccounts().getFirst();
                    assertTrue(reactivated.enabled()); assertEquals(edit.folderScope(), reactivated.folderScope());
                } else {
                    assertNull(saved.credentialRef()); assertFalse(credentials.entries.containsKey(active.credentialRef()));
                }
            }
        }
    }
    @Test void legacySessionAndWindowsStatesRemainReadableWithoutRewriteOrAutomaticMigration() throws Exception {
        for (String mode : List.of("session", "windows")) {
            Path scenario = root.resolve(mode);
            try (var store = new IntegrationStore(scenario); var credentials = new TestSecureCredentialStore();
                 var sessions = ChatSessionStore.openExclusive(scenario.resolve("memory"))) {
                String reference = credentials.write(fresh().credentialTarget(), "synthetic-legacy".toCharArray(), mode);
                var legacy = account(true, 1, "selected", List.of("INBOX"), false, "synthetic@qq.com", "qq", "Old")
                        .withCredential(reference, mode);
                store.save(0, config(0, legacy)); byte[] saved = Files.readAllBytes(store.path());
                try (var manager = manager(store, credentials, sessions, new AtomicInteger())) {
                    assertEquals(legacy, manager.config().mailAccounts().getFirst()); manager.statuses();
                    assertArrayEquals(saved, Files.readAllBytes(store.path())); assertEquals(1, credentials.writes);
                }
            }
        }
    }
}
