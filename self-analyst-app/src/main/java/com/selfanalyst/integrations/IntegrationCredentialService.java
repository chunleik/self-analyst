package com.selfanalyst.integrations;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/** New reference -> optimistic metadata commit -> old reference cleanup, never the reverse. */
public final class IntegrationCredentialService {
    private final IntegrationStore store;
    private final CredentialStore credentials;
    private final Set<String> cleanup = new HashSet<>();
    public IntegrationCredentialService(IntegrationStore store, CredentialStore credentials) {
        this.store = store; this.credentials = credentials;
    }
    public record Result(IntegrationConfig config, boolean cleanupPending) {}

    public synchronized Result replace(long expectedRevision, String id, char[] secret, String mode) throws IOException {
        retryCleanup();
        IntegrationConfig before = current(expectedRevision);
        var server = before.mcpServers().stream().filter(s -> s.id().equals(id)).findFirst();
        var account = before.mailAccounts().stream().filter(a -> a.id().equals(id)).findFirst();
        if (server.isEmpty() && account.isEmpty()) throw new IOException("unknown_connection");
        if (server.isPresent() && !server.get().authType().equals("bearer")) throw new IOException("unexpected_credential");
        CredentialTarget target = server.isPresent() ? server.get().credentialTarget() : account.get().credentialTarget();
        String previous = server.isPresent() ? server.get().credentialRef() : account.get().credentialRef();
        String storageMode = mode == null ? "session" : mode;
        String reference = credentials.write(target, secret, storageMode);
        IntegrationConfig committed;
        try {
            IntegrationConfig candidate = new IntegrationConfig(1, before.revision(),
                    before.mcpServers().stream().map(s -> s.id().equals(id) ? s.withCredential(reference, storageMode) : s).toList(),
                    before.mailAccounts().stream().map(a -> a.id().equals(id) ? a.withCredential(reference, storageMode) : a).toList(),
                    before.toolApprovals());
            committed = store.save(expectedRevision, candidate);
        } catch (IOException | RuntimeException failure) {
            clean(reference);
            throw failure;
        }
        clean(previous);
        return new Result(committed, !cleanup.isEmpty());
    }
    public synchronized Result disconnect(long expectedRevision, String id) throws IOException {
        retryCleanup();
        IntegrationConfig before = current(expectedRevision);
        var server = before.mcpServers().stream().filter(s -> s.id().equals(id)).findFirst();
        var account = before.mailAccounts().stream().filter(a -> a.id().equals(id)).findFirst();
        if (server.isEmpty() && account.isEmpty()) throw new IOException("unknown_connection");
        String previous = server.isPresent() ? server.get().credentialRef() : account.get().credentialRef();
        IntegrationConfig candidate = new IntegrationConfig(1, before.revision(),
                before.mcpServers().stream().map(s -> s.id().equals(id) ? s.disconnected() : s).toList(),
                before.mailAccounts().stream().map(a -> a.id().equals(id) ? a.disconnected() : a).toList(),
                before.toolApprovals().stream().filter(a -> !a.sourceId().equals(id)).toList());
        IntegrationConfig committed = store.save(expectedRevision, candidate);
        clean(previous); // Local eligibility is revoked durably before cleanup.
        return new Result(committed, !cleanup.isEmpty());
    }
    public synchronized boolean cleanupPending() { return !cleanup.isEmpty(); }
    public synchronized void discard(String reference) { clean(reference); }
    public synchronized boolean retryCleanup() {
        for (String reference : Set.copyOf(cleanup)) clean(reference);
        return cleanup.isEmpty();
    }
    private IntegrationConfig current(long revision) throws IOException {
        IntegrationConfig current = store.snapshot();
        if (current.revision() != revision) throw new IOException("revision_conflict");
        return current;
    }
    private void clean(String reference) {
        if (reference == null) return;
        try { credentials.delete(reference); cleanup.remove(reference); }
        catch (IOException | RuntimeException ignored) { cleanup.add(reference); }
    }
}
