package com.selfanalyst.integrations;

import java.io.IOException;
import java.util.Arrays;
import java.util.Optional;

/** Internal-only secret access. HTTP controllers must expose write/configured/delete, never read. */
public interface CredentialStore extends AutoCloseable {
    String write(CredentialTarget target, char[] secret, String storageMode) throws IOException;
    Optional<char[]> read(String reference, CredentialTarget target) throws IOException;
    void delete(String reference) throws IOException;
    /** Failed write verification may leave an uncommitted vault item; never hide cleanup failure. */
    default boolean cleanupPending() { return false; }
    default boolean retryCleanup() { return !cleanupPending(); }
    default boolean configured(String reference, CredentialTarget target) {
        if (reference == null) return false;
        try {
            Optional<char[]> value = read(reference, target);
            value.ifPresent(secret -> Arrays.fill(secret, '\0'));
            return value.isPresent();
        } catch (IOException | RuntimeException unavailable) { return false; }
    }
    @Override void close();
    static void validateSecret(char[] secret) throws IOException {
        if (secret == null || secret.length == 0 || secret.length > 2048) throw new IOException("invalid_credential");
        for (char c : secret) if (Character.isISOControl(c)) throw new IOException("invalid_credential");
    }
}
