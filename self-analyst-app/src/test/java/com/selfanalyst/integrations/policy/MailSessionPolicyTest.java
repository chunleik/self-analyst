package com.selfanalyst.integrations.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfanalyst.config.LlmSettings;
import com.selfanalyst.desktop.store.ChatSessionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;

class MailSessionPolicyTest {
    @TempDir Path root;
    private static final String TARGET = "https://model.example/v1";
    @Test void markerAndConsentSurviveReopenAndDatabaseBackup() throws Exception {
        String id;
        try (var store = new ChatSessionStore(root)) {
            id = store.create(new ChatSessionStore.CreateRequest()).id;
            var policy = new MailSessionPolicy(store);
            assertThrows(MailSessionPolicy.ModelConsentRequiredException.class, () -> policy.beforeMailResult(id, TARGET));
            assertFalse(policy.containsMail(id));
            policy.consentTarget(id, "https://MODEL.example:443/v1/");
            policy.beforeMailResult(id, TARGET);
            assertTrue(store.getSession(id).mailOrigin);
            assertEquals("smart", store.getSession(id).memoryPolicy);
            assertThrows(MailSessionPolicy.ModelConsentRequiredException.class,
                    () -> policy.requireModelTarget(id, "https://other.example/v1"));
        }
        Path restored = root.resolve("restore"); Files.createDirectories(restored.resolve("chat-sessions"));
        Files.copy(root.resolve("chat-sessions/chat.db"), restored.resolve("chat-sessions/chat.db"));
        try (var store = new ChatSessionStore(restored)) {
            var policy = new MailSessionPolicy(store);
            assertTrue(policy.containsMail(id));
            policy.requireModelTarget(id, TARGET);
            assertFalse(policy.automaticMemoryAllowed(id));
        }
    }
    @Test void markerWriteFailureDoesNotPermitResult() throws Exception {
        try (var store = new ChatSessionStore(root)) {
            String id = store.create(new ChatSessionStore.CreateRequest()).id;
            var policy = new MailSessionPolicy(store); policy.consentTarget(id, TARGET);
            try (var c = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("chat-sessions/chat.db")); var s = c.createStatement()) {
                s.execute("CREATE TRIGGER reject_mail BEFORE INSERT ON mail_session_origins BEGIN SELECT RAISE(ABORT,'synthetic'); END");
            }
            var exposed = new java.util.concurrent.atomic.AtomicBoolean();
            assertThrows(IllegalStateException.class, () -> { policy.beforeMailResult(id, TARGET); exposed.set(true); });
            assertFalse(exposed.get()); assertFalse(policy.containsMail(id));
        }
    }
    @Test void deletionCascadesAndClientCannotSetProvenance() throws Exception {
        try (var store = new ChatSessionStore(root)) {
            String id = store.create(new ChatSessionStore.CreateRequest()).id;
            var policy = new MailSessionPolicy(store); policy.consentTarget(id, TARGET); policy.beforeMailResult(id, TARGET);
            store.updateMeta(id, "Renamed", null, java.util.Map.of("mailOrigin", false));
            store.updateMemoryPolicy(id, "smart"); assertTrue(policy.containsMail(id));
            assertFalse(new ObjectMapper().readValue("{\"mailOrigin\":true}", ChatSessionStore.Session.class).mailOrigin);
            store.delete(id);
            assertThrows(IllegalStateException.class, () -> policy.beforeMailResult(id, TARGET));
            try (var c = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("chat-sessions/chat.db")); var s = c.createStatement()) {
                for (String table : java.util.List.of("mail_session_origins", "mail_model_targets")) {
                    try (var rows = s.executeQuery("SELECT count(*) FROM " + table)) { assertTrue(rows.next()); assertEquals(0, rows.getInt(1)); }
                }
            }
        }
    }
    @Test void unavailableStorageFailsClosed() {
        var store = new ChatSessionStore(root); String id = store.create(new ChatSessionStore.CreateRequest()).id;
        var policy = new MailSessionPolicy(store); store.close();
        assertThrows(IllegalStateException.class, () -> policy.beforeMailResult(id, TARGET));
        assertThrows(IllegalStateException.class, () -> policy.requireModelTarget(id, TARGET));
        assertFalse(policy.automaticMemoryAllowed(id));
    }
    @Test void incompleteProvenanceDoesNotTriggerSilentLegacyRecovery() throws Exception {
        try (var store = new ChatSessionStore(root)) {
            String id=store.create(new ChatSessionStore.CreateRequest()).id;
            var policy=new MailSessionPolicy(store); policy.consentTarget(id,TARGET); policy.beforeMailResult(id,TARGET);
        }
        Path database=root.resolve("chat-sessions/chat.db");
        try (var c=DriverManager.getConnection("jdbc:sqlite:"+database);var statement=c.createStatement()) {
            statement.execute("DROP TABLE mail_session_origins");
        }
        assertThrows(IllegalStateException.class, () -> new ChatSessionStore(root));
        try (var c=DriverManager.getConnection("jdbc:sqlite:"+database);var statement=c.createStatement();var rows=statement.executeQuery("SELECT count(*) FROM sessions")) {
            assertTrue(rows.next());assertEquals(1,rows.getInt(1));
        }
    }
    @Test void recipientIgnoresCredentialAndModelRotationButNotPathOrHost() {
        var before = new LlmSettings("secret-a", "https://MODEL.example:443/v1/", "one", .2);
        var after = new LlmSettings("secret-b", TARGET, "two", .8);
        assertEquals(ModelRecipient.from(before), ModelRecipient.from(after));
        assertNotEquals(ModelRecipient.from(after), ModelRecipient.normalize("https://model.example/other"));
        assertNotEquals(ModelRecipient.normalize("https://model.example/tenant%2Fother"), ModelRecipient.normalize("https://model.example/tenant/other"));
        assertThrows(IllegalArgumentException.class, () -> ModelRecipient.normalize("https://secret@model.example/v1"));
        assertThrows(IllegalArgumentException.class, () -> ModelRecipient.normalize("https://model.example/v1?key=secret"));
    }
}
