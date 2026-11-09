package com.selfanalyst.integrations;

import java.io.IOException;
import java.util.Optional;

/** Session mode by default. Windows persistence is explicit and never silently downgraded. */
public final class IntegrationCredentialStore implements CredentialStore {
    private final SessionCredentialStore session = new SessionCredentialStore();
    private WindowsCredentialStore windows;
    private boolean closed;
    @Override public synchronized String write(CredentialTarget target, char[] secret, String mode) throws IOException {
        checkOpen();
        if (mode == null || mode.equals("session")) return session.write(target, secret, "session");
        if (mode.equals("windows")) return windows().write(target, secret, mode);
        throw new IOException("unsupported_storage_mode");
    }
    @Override public synchronized Optional<char[]> read(String ref, CredentialTarget target) throws IOException {
        checkOpen();
        if (ref == null) return Optional.empty();
        IntegrationConfig.validateRef(ref);
        return ref.startsWith("windows:") ? windows().read(ref, target) : session.read(ref, target);
    }
    @Override public synchronized void delete(String ref) throws IOException {
        checkOpen();
        if (ref == null) return;
        IntegrationConfig.validateRef(ref);
        if (ref.startsWith("windows:")) windows().delete(ref); else session.delete(ref);
    }
    private WindowsCredentialStore windows() throws IOException {
        if (windows == null) windows = new WindowsCredentialStore();
        return windows;
    }
    private void checkOpen() throws IOException { if (closed) throw new IOException("credential_store_closed"); }
    @Override public synchronized void close() {
        session.close(); if (windows != null) windows.close(); closed = true;
    }
}
