package com.selfanalyst.integrations;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.Arrays;
import java.util.function.Supplier;

/** New reference -> optimistic metadata commit -> old reference cleanup, never the reverse. */
public final class IntegrationCredentialService {
    private final IntegrationStore store;
    private final CredentialStore credentials;
    private final Set<String> cleanup = new HashSet<>();
    // A failed response is not proof that an atomic filesystem replacement did not happen.
    // These references must be reconciled against readable metadata before any deletion.
    private final Set<String> uncertain = new HashSet<>();
    public IntegrationCredentialService(IntegrationStore store, CredentialStore credentials) {
        this.store = store; this.credentials = credentials;
    }
    public record Result(IntegrationConfig config, boolean cleanupPending) {}

    /** No mail/network operation: verified secure persistence precedes the single authorization commit. */
    public synchronized Result activateMail(long expectedRevision, String id, int disclosureVersion,
            String shownModelTarget, char[] secret, Supplier<String> currentModelTarget) throws IOException {
        if (disclosureVersion != 1 || shownModelTarget == null || shownModelTarget.isBlank())
            throw new IOException("mail_activation_confirmation_required");
        IntegrationStore.CommitGuard confirmation = () -> {
            String actual = currentModelTarget.get();
            if (actual == null || actual.isBlank()) throw new IOException("model_target_unavailable");
            if (!actual.equals(shownModelTarget)) throw new IOException("model_target_changed");
        };
        confirmation.check();
        IntegrationConfig before = current(expectedRevision);
        var account = before.mailAccounts().stream().filter(a -> a.id().equals(id)).findFirst()
                .orElseThrow(() -> new IOException("unknown_connection"));
        if (secret != null) CredentialStore.validateSecret(secret);
        retryCleanup();
        String reference = account.credentialRef();
        boolean created = false;
        try {
            if (secret != null) {
                confirmation.check();
                reference = credentials.write(account.credentialTarget(), secret, "windows");
                created = true;
                if (reference == null || !reference.startsWith("windows:"))
                    throw new IOException("credential_verification_failed");
                IntegrationConfig.validateRef(reference);
            } else if (!"windows".equals(account.storageMode()) || reference == null) {
                throw new IOException("mail_credential_required");
            }
            // The native adapter also verifies writes; this boundary verifies reuse and alternate stores.
            var verified = credentials.read(reference, account.credentialTarget());
            try {
                if (verified.isEmpty() || verified.get().length == 0
                        || (secret != null && !Arrays.equals(secret, verified.get())))
                    throw new IOException(secret == null ? "mail_credential_required" : "credential_verification_failed");
                CredentialStore.validateSecret(verified.get());
            } finally { verified.ifPresent(value -> Arrays.fill(value, '\0')); }
            String committedReference = reference;
            IntegrationConfig candidate = new IntegrationConfig(1, before.revision(), before.mcpServers(),
                    before.mailAccounts().stream().map(a -> a.id().equals(id) ? a.activated(committedReference) : a).toList(),
                    before.toolApprovals());
            // Includes the no-secret path. A target changed during vault I/O cannot become authorized.
            IntegrationConfig committed = commitActivation(expectedRevision, candidate, confirmation, reference, account.credentialRef());
            if (created && !reference.equals(account.credentialRef())) clean(account.credentialRef());
            return new Result(committed, cleanupPending());
        } catch (IOException | RuntimeException failure) {
            if (created && !uncertain.contains(reference)) clean(reference);
            throw failure;
        }
    }
    private IntegrationConfig commitActivation(long revision, IntegrationConfig candidate, IntegrationStore.CommitGuard guard,
            String reference, String previousReference) throws IOException {
        try { return store.save(revision, candidate, guard); }
        catch (IOException | RuntimeException failure) {
            IntegrationConfig persisted;
            try { persisted = store.snapshot(); }
            catch (IOException | RuntimeException unreadable) {
                uncertain.add(reference);
                if (previousReference != null) uncertain.add(previousReference);
                throw new IOException("mail_activation_outcome_uncertain");
            }
            if (revision < Long.MAX_VALUE && persisted.equals(candidate.withRevision(revision + 1))) return persisted;
            if (persisted.revision() > revision && references(persisted, reference)) {
                uncertain.add(reference);
                if (previousReference != null) uncertain.add(previousReference);
                throw new IOException("mail_activation_outcome_uncertain");
            }
            throw failure;
        }
    }
    private static boolean references(IntegrationConfig config, String reference) {
        return config.mailAccounts().stream().anyMatch(a -> java.util.Objects.equals(reference, a.credentialRef()))
                || config.mcpServers().stream().anyMatch(s -> java.util.Objects.equals(reference, s.credentialRef()));
    }

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
        return new Result(committed, cleanupPending());
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
        return new Result(committed, cleanupPending());
    }
    public synchronized boolean cleanupPending() { return !cleanup.isEmpty() || credentials.cleanupPending(); }
    public synchronized void discard(String reference) { clean(reference); }
    public synchronized boolean retryCleanup() {
        credentials.retryCleanup();
        if (!uncertain.isEmpty()) {
            try {
                IntegrationConfig persisted = store.snapshot();
                for (String reference : Set.copyOf(uncertain)) {
                    if (!references(persisted, reference)) clean(reference);
                    uncertain.remove(reference);
                }
            } catch (IOException | RuntimeException unreadable) { /* Keep possibly committed credentials intact. */ }
        }
        for (String reference : Set.copyOf(cleanup)) clean(reference);
        return !cleanupPending() && uncertain.isEmpty();
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
