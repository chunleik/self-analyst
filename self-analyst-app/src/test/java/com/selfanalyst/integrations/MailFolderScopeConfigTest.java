package com.selfanalyst.integrations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class MailFolderScopeConfigTest {
    @TempDir Path root;
    private static final ObjectMapper JSON = new ObjectMapper();

    private static IntegrationConfig.MailAccount account(String scope, List<String> folders) {
        return new IntegrationConfig.MailAccount("mail-one", "qq", "synthetic@qq.com", "Synthetic", true,
                scope, folders, false, null, "session", 1);
    }
    private static IntegrationConfig config(IntegrationConfig.MailAccount account) {
        return new IntegrationConfig(1, 0, List.of(), List.of(account), List.of());
    }
    private static ObjectNode document(String scope, List<String> folders) {
        return JSON.valueToTree(config(account(scope, folders)));
    }
    private static ObjectNode mail(ObjectNode document) { return (ObjectNode) document.path("mailAccounts").get(0); }

    @Test void missingScopeIsRejectedWithoutMigrationOrRewriting() throws Exception {
        var document = document("selected", List.of("INBOX", "Projects/2026", "*"));
        mail(document).remove("folderScope");
        Path file = root.resolve("config/integrations.json"); Files.createDirectories(file.getParent());
        String original = JSON.writeValueAsString(document); Files.writeString(file, original);
        assertThrows(IOException.class, () -> new IntegrationStore(root));
        assertEquals(original, Files.readString(file));
        assertFalse(Files.exists(file.resolveSibling("integrations.json.bak")));
    }

    @Test void strictScopeRejectsNullTypesUnknownValuesAndStillRejectsOtherMissingFields() throws Exception {
        var valid = document("all", List.of());
        assertEquals("all", IntegrationStore.parse(JSON.writeValueAsString(valid)).mailAccounts().getFirst().folderScope());
        for (String value : List.of("null", "true", "7", "[]", "{}", "\"ALL\"", "\"\"", "\"selected \"", "\"*\"")) {
            var invalid = valid.deepCopy(); mail(invalid).set("folderScope", JSON.readTree(value));
            assertThrows(IOException.class, () -> IntegrationStore.parse(JSON.writeValueAsString(invalid)), value);
        }
        for (String field : List.of("folderScope", "allowedFolders", "enabled", "bodyAccess", "credentialRef", "storageMode", "disclosureVersion")) {
            var invalid = valid.deepCopy(); mail(invalid).remove(field);
            assertThrows(IOException.class, () -> IntegrationStore.parse(JSON.writeValueAsString(invalid)), field);
        }
        var unknown = valid.deepCopy(); mail(unknown).put("folderPattern", "*");
        assertThrows(IOException.class, () -> IntegrationStore.parse(JSON.writeValueAsString(unknown)));
        String json = JSON.writeValueAsString(valid);
        assertThrows(IOException.class, () -> IntegrationStore.parse(json.replace("\"folderScope\":\"all\"", "\"folderScope\":\"all\",\"folderScope\":\"selected\"")));
        assertThrows(IOException.class, () -> IntegrationStore.parse(json + " {}"));
    }

    @Test void explicitScopeControlsEmptyListAndKeepsSelectedBackupBounded() throws Exception {
        assertEquals(List.of(), account("all", List.of()).allowedFolders());
        assertEquals(List.of("INBOX"), account("all", List.of("INBOX")).allowedFolders());
        assertThrows(IllegalArgumentException.class, () -> account("selected", List.of()));
        assertThrows(IllegalArgumentException.class, () -> account(null, List.of("INBOX")));
        List<String> maximum = IntStream.range(0, 256).mapToObj(i -> "Projects/" + i).toList();
        assertEquals(maximum, account("selected", maximum).allowedFolders());
        for (String scope : List.of("all", "selected")) {
            for (List<String> folders : List.of(List.of("INBOX", "INBOX"), List.of("x".repeat(257)),
                    List.of("Bad\nName"), List.of(" "), IntStream.range(0, 257).mapToObj(i -> "F" + i).toList())) {
                assertThrows(IllegalArgumentException.class, () -> account(scope, folders));
            }
        }
        var oldEmpty = document("all", List.of()); mail(oldEmpty).remove("folderScope");
        assertThrows(IOException.class, () -> IntegrationStore.parse(JSON.writeValueAsString(oldEmpty)));
    }

    @Test void portableRoundTripsExplicitScopesWithoutReenablingOrCredentials() throws Exception {
        for (String scope : List.of("all", "selected")) {
            try (var store = new IntegrationStore(root.resolve(scope))) {
                var original = account(scope, List.of("INBOX", "Archive/2026")).withCredential(
                        "session:12345678-1234-1234-1234-123456789abc", "session");
                store.save(0, config(original));
                String exported = store.exportMetadata();
                var portable = IntegrationStore.parse(exported).mailAccounts().getFirst();
                assertEquals(scope, portable.folderScope()); assertEquals(original.allowedFolders(), portable.allowedFolders());
                assertFalse(portable.enabled()); assertFalse(portable.bodyAccess());
                assertNull(portable.credentialRef()); assertEquals(0, portable.disclosureVersion());
                var imported = store.importMetadata(1, exported).mailAccounts().getFirst();
                assertEquals(portable, imported);
                if (scope.equals("selected")) {
                    var old = JSON.readTree(exported); ((ObjectNode) old.path("mailAccounts").get(0)).remove("folderScope");
                    assertThrows(IOException.class, () -> store.importMetadata(2, JSON.writeValueAsString(old)));
                }
                assertTrue(JSON.writeValueAsString(store.publicMetadata(new SessionCredentialStore())).contains("\"folderScope\":\"" + scope + "\""));
            }
        }
    }

    @Test void credentialReplacementDisconnectAndPortableClonesPreserveScopeAndFolders() throws Exception {
        for (String scope : List.of("all", "selected")) {
            try (var store = new IntegrationStore(root.resolve(scope)); var credentials = new SessionCredentialStore()) {
                var original = account(scope, List.of("INBOX", "Projects/Nested"));
                store.save(0, config(original));
                var service = new IntegrationCredentialService(store, credentials);
                var first = service.replace(1, original.id(), "synthetic-old".toCharArray(), "session").config().mailAccounts().getFirst();
                var second = service.replace(2, original.id(), "synthetic-new".toCharArray(), "session").config().mailAccounts().getFirst();
                assertFalse(credentials.configured(first.credentialRef(), original.credentialTarget()));
                assertTrue(credentials.configured(second.credentialRef(), original.credentialTarget()));
                var disconnected = service.disconnect(3, original.id()).config().mailAccounts().getFirst();
                for (var copy : List.of(first, second, disconnected, config(original).portable().mailAccounts().getFirst(),
                        original.withCredential("windows:12345678-1234-1234-1234-123456789abc", "windows"))) {
                    assertEquals(scope, copy.folderScope()); assertEquals(original.allowedFolders(), copy.allowedFolders());
                    assertFalse(copy.bodyAccess());
                }
                assertFalse(disconnected.enabled()); assertNull(disconnected.credentialRef());
                assertFalse(credentials.configured(second.credentialRef(), original.credentialTarget()));
            }
        }
    }

    @Test void failedScopeSaveLeavesPreviouslyAuthorizedScopeIntact() throws Exception {
        var initial = account("selected", List.of("INBOX"));
        try (var store = new IntegrationStore(root)) { store.save(0, config(initial)); }
        try (var failing = new IntegrationStore(root, (source, target) -> { throw new IOException("synthetic_failure"); })) {
            assertThrows(IOException.class, () -> failing.save(1, config(account("all", List.of("INBOX"))).withRevision(1)));
            assertEquals(initial, failing.snapshot().mailAccounts().getFirst());
        }
    }
}
