package com.selfanalyst.integrations;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class IntegrationCredentialStoreTest {
    @TempDir Path root;
    @Test void sessionBindingCopiesSecretsAndClearsOnDisconnectAndClose() throws Exception {
        var store = new SessionCredentialStore();
        var target = CredentialTarget.mcp("one", "https://mcp.example.com/a");
        char[] secret = "synthetic-bearer".toCharArray();
        String reference = store.write(target, secret, "session");
        Arrays.fill(secret, '\0');
        assertArrayEquals("synthetic-bearer".toCharArray(), store.read(reference, target).orElseThrow());
        assertTrue(store.read(reference, CredentialTarget.mcp("two", "https://mcp.example.com/a")).isEmpty());
        assertTrue(store.read(reference, CredentialTarget.mcp("one", "https://mcp.example.com/b")).isEmpty());
        store.delete(reference); assertFalse(store.configured(reference, target));
        reference = store.write(target, "another".toCharArray(), "session"); store.close();
        assertFalse(store.configured(reference, target));
        assertThrows(IOException.class, () -> store.write(target, "another".toCharArray(), "session"));
    }
    @Test @DisabledOnOs(OS.WINDOWS) void unsupportedVaultNeverFallsBackToDiskOrSession() throws Exception {
        try (var store = new IntegrationCredentialStore()) {
            var target = CredentialTarget.mcp("one", "https://mcp.example.com/a");
            IOException error = assertThrows(IOException.class, () -> store.write(target, "secret".toCharArray(), "windows"));
            assertEquals("persistent_credentials_unavailable", error.getMessage());
            assertThrows(IOException.class, WindowsCredentialStore::new);
        }
    }
    @Test void replacementAndDisconnectCommitBeforeDeletingOldReference() throws Exception {
        var config = new IntegrationStore(root); config.save(0, IntegrationStoreTest.config(0, null));
        var credentials = new SessionCredentialStore();
        var service = new IntegrationCredentialService(config, credentials);
        var first = service.replace(1, "mcp-one", "old".toCharArray(), "session").config();
        String old = first.mcpServers().getFirst().credentialRef();
        var second = service.replace(2, "mcp-one", "new".toCharArray(), "session").config();
        String newer = second.mcpServers().getFirst().credentialRef();
        assertFalse(credentials.configured(old, IntegrationStoreTest.server(null).credentialTarget()));
        assertTrue(credentials.configured(newer, IntegrationStoreTest.server(null).credentialTarget()));
        var disconnected = service.disconnect(3, "mcp-one").config();
        assertNull(disconnected.mcpServers().getFirst().credentialRef());
        assertFalse(credentials.configured(newer, IntegrationStoreTest.server(null).credentialTarget()));
        assertThrows(IOException.class, () -> service.replace(3, "mcp-one", "stale".toCharArray(), "session"));
    }
    @Test void failedCommitPreservesOldSecretAndCompensatesNewReference() throws Exception {
        var normal = new IntegrationStore(root);
        var credentials = new RecordingStore();
        String old = credentials.write(IntegrationStoreTest.server(null).credentialTarget(), "old".toCharArray(), "session");
        normal.save(0, IntegrationStoreTest.config(0, old));
        var failing = new IntegrationStore(root, (from, to) -> { throw new IOException("synthetic_failure"); });
        var service = new IntegrationCredentialService(failing, credentials);
        assertThrows(IOException.class, () -> service.replace(1, "mcp-one", "new".toCharArray(), "session"));
        assertEquals(old, normal.snapshot().mcpServers().getFirst().credentialRef());
        assertTrue(credentials.configured(old, IntegrationStoreTest.server(null).credentialTarget()));
        assertFalse(credentials.configured(credentials.lastWritten, IntegrationStoreTest.server(null).credentialTarget()));
    }
    @Test void cleanupFailureRemainsRetryableWithoutRollingBackCommittedCredentials() throws Exception {
        var normal = new IntegrationStore(root); normal.save(0, IntegrationStoreTest.config(0, null));
        var credentials = new RecordingStore(); var service = new IntegrationCredentialService(normal, credentials);
        service.replace(1, "mcp-one", "old".toCharArray(), "session");
        credentials.failDelete = true;
        var result = service.replace(2, "mcp-one", "new".toCharArray(), "session");
        assertTrue(result.cleanupPending()); assertTrue(service.cleanupPending()); assertEquals(3, normal.snapshot().revision());
        credentials.failDelete = false; assertTrue(service.retryCleanup()); assertFalse(service.cleanupPending());
    }
    @Test void disconnectDuringCredentialCreationCannotRestoreRevokedAccount() throws Exception {
        var normal = new IntegrationStore(root); normal.save(0, IntegrationStoreTest.config(0, null));
        var credentials = new RecordingStore();
        var service = new IntegrationCredentialService(normal, credentials);
        var other = new IntegrationCredentialService(normal, credentials);
        credentials.afterWrite = () -> other.disconnect(1, "mcp-one");
        assertThrows(IOException.class, () -> service.replace(1, "mcp-one", "raced".toCharArray(), "session"));
        assertEquals(2, normal.snapshot().revision());
        assertNull(normal.snapshot().mcpServers().getFirst().credentialRef());
        assertFalse(credentials.configured(credentials.lastWritten, IntegrationStoreTest.server(null).credentialTarget()));
    }
    @FunctionalInterface private interface AfterWrite { void run() throws IOException; }
    private static final class RecordingStore implements CredentialStore {
        final SessionCredentialStore delegate = new SessionCredentialStore(); String lastWritten; boolean failDelete; AfterWrite afterWrite;
        public String write(CredentialTarget target, char[] secret, String mode) throws IOException { lastWritten = delegate.write(target, secret, mode); if (afterWrite != null) afterWrite.run(); return lastWritten; }
        public Optional<char[]> read(String reference, CredentialTarget target) throws IOException { return delegate.read(reference, target); }
        public void delete(String reference) throws IOException { if (failDelete) throw new IOException("synthetic_failure"); delegate.delete(reference); }
        public void close() { delegate.close(); }
    }
}
