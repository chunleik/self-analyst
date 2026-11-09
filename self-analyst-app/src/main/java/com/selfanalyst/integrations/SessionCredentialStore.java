package com.selfanalyst.integrations;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Default store. Erases owned arrays on replacement, disconnect and shutdown. */
public final class SessionCredentialStore implements CredentialStore {
    private record Entry(CredentialTarget target, char[] secret) {}
    private final Map<String, Entry> values = new HashMap<>();
    private boolean closed;
    @Override public synchronized String write(CredentialTarget target, char[] secret, String mode) throws IOException {
        checkOpen();
        if (!"session".equals(mode)) throw new IOException("persistent_credentials_unavailable");
        CredentialStore.validateSecret(secret);
        if (target == null) throw new IOException("invalid_credential_target");
        if (values.size() >= 256) throw new IOException("credential_limit");
        String ref = "session:" + UUID.randomUUID();
        values.put(ref, new Entry(target, secret.clone()));
        return ref;
    }
    @Override public synchronized Optional<char[]> read(String ref, CredentialTarget target) throws IOException {
        checkOpen();
        Entry entry = values.get(ref);
        return entry != null && entry.target.equals(target) ? Optional.of(entry.secret.clone()) : Optional.empty();
    }
    @Override public synchronized void delete(String ref) throws IOException {
        checkOpen();
        Entry removed = values.remove(ref);
        if (removed != null) Arrays.fill(removed.secret, '\0');
    }
    private void checkOpen() throws IOException { if (closed) throw new IOException("credential_store_closed"); }
    @Override public synchronized void close() {
        values.values().forEach(entry -> Arrays.fill(entry.secret, '\0'));
        values.clear(); closed = true;
    }
}
