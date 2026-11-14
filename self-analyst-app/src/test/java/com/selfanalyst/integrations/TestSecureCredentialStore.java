package com.selfanalyst.integrations;

import java.io.IOException;
import java.util.*;

/** Synthetic in-memory secure-store boundary for transaction tests; does not implement encryption. */
public final class TestSecureCredentialStore implements CredentialStore {
    public record Entry(CredentialTarget target, char[] secret) {}
    public final Map<String, Entry> entries = new HashMap<>();
    public int writes, reads, deletes;
    public String lastWritten, writeFailure, readFailure;
    public boolean failDelete, missingRead, wrongRead;
    public IoAction afterWrite, afterRead, beforeDelete;
    @FunctionalInterface public interface IoAction { void run() throws IOException; }
    @Override public String write(CredentialTarget target, char[] secret, String mode) throws IOException {
        writes++;
        if (writeFailure != null) throw new IOException(writeFailure);
        CredentialStore.validateSecret(secret);
        lastWritten = mode + ":" + UUID.randomUUID();
        entries.put(lastWritten, new Entry(target, secret.clone()));
        if (afterWrite != null) afterWrite.run();
        return lastWritten;
    }
    @Override public Optional<char[]> read(String reference, CredentialTarget target) throws IOException {
        reads++;
        if (readFailure != null) throw new IOException(readFailure);
        var entry = entries.get(reference);
        Optional<char[]> result = entry == null || !entry.target().equals(target) || missingRead ? Optional.empty()
                : Optional.of(wrongRead ? "synthetic-wrong".toCharArray() : entry.secret().clone());
        if (afterRead != null) afterRead.run();
        return result;
    }
    @Override public void delete(String reference) throws IOException {
        deletes++;
        if (beforeDelete != null) beforeDelete.run();
        if (failDelete) throw new IOException("credential_delete_failed");
        var removed = entries.remove(reference);
        if (removed != null) Arrays.fill(removed.secret(), '\0');
    }
    @Override public void close() {
        entries.values().forEach(value -> Arrays.fill(value.secret(), '\0'));
        entries.clear();
    }
}
