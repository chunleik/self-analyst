package com.selfanalyst.integrations;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.Set;
import java.util.HashSet;
import java.util.Objects;

/** Windows current-user generic credentials. Not an isolation boundary against same-user processes. */
public final class WindowsCredentialStore implements CredentialStore {
    private static final int TYPE_GENERIC = 1, PERSIST_LOCAL_MACHINE = 2, NOT_FOUND = 1168;
    private static final int MAX_BLOB = 2560;
    private final Vault api;
    private final Set<String> cleanup = new HashSet<>();
    private boolean closed;

    public interface Vault extends StdCallLibrary {
        boolean CredWriteW(Credential value, int flags);
        boolean CredReadW(WString target, int type, int flags, PointerByReference value);
        boolean CredDeleteW(WString target, int type, int flags);
        void CredFree(Pointer buffer);
    }
    @Structure.FieldOrder({"Flags", "Type", "TargetName", "Comment", "LastWritten", "CredentialBlobSize",
            "CredentialBlob", "Persist", "AttributeCount", "Attributes", "TargetAlias", "UserName"})
    public static class Credential extends Structure {
        public int Flags, Type;
        public WString TargetName, Comment;
        public WinBase.FILETIME LastWritten;
        public int CredentialBlobSize;
        public Pointer CredentialBlob;
        public int Persist, AttributeCount;
        public Pointer Attributes;
        public WString TargetAlias, UserName;
        public Credential() { LastWritten = new WinBase.FILETIME(); }
        public Credential(Pointer pointer) { super(pointer); LastWritten = new WinBase.FILETIME(); read(); }
    }
    public WindowsCredentialStore() throws IOException {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows"))
            throw new IOException("persistent_credentials_unavailable");
        try { api = Native.load("Advapi32", Vault.class); }
        catch (LinkageError | RuntimeException unavailable) { throw new IOException("persistent_credentials_unavailable"); }
    }
    /** Deterministic native-boundary tests use synthetic vault records, never a real user vault. */
    WindowsCredentialStore(Vault api) { this.api = Objects.requireNonNull(api); }
    @Override public synchronized String write(CredentialTarget target, char[] secret, String mode) throws IOException {
        checkOpen();
        if (!"windows".equals(mode)) throw new IOException("unsupported_storage_mode");
        CredentialStore.validateSecret(secret);
        if (target == null) throw new IOException("invalid_credential_target");
        String reference = "windows:" + UUID.randomUUID();
        boolean written = false;
        ByteBuffer encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(secret));
        byte[] bytes = new byte[4 + 32 + encoded.remaining()];
        if (bytes.length > MAX_BLOB) {
            if (encoded.hasArray()) Arrays.fill(encoded.array(), (byte) 0);
            throw new IOException("credential_limit");
        }
        ByteBuffer.wrap(bytes).putInt(1).put(binding(target)).put(encoded);
        if (encoded.hasArray()) Arrays.fill(encoded.array(), (byte) 0);
        try (Memory memory = new Memory(bytes.length)) {
            try {
                memory.write(0, bytes, 0, bytes.length);
                Credential credential = new Credential();
                credential.Type = TYPE_GENERIC;
                credential.TargetName = new WString(item(reference));
                credential.UserName = new WString("SelfAnalyst");
                credential.CredentialBlobSize = bytes.length;
                credential.CredentialBlob = memory;
                credential.Persist = PERSIST_LOCAL_MACHINE;
                if (!api.CredWriteW(credential, 0)) throw new IOException("credential_write_failed");
                written = true;
                // Explicitly verify this adapter can round-trip before reporting persistence success.
                Optional<char[]> verified = read(reference, target);
                boolean equal = verified.isPresent() && Arrays.equals(secret, verified.get());
                verified.ifPresent(chars -> Arrays.fill(chars, '\0'));
                if (!equal) throw new IOException("credential_verification_failed");
                return reference;
            } finally { memory.clear(); }
        } catch (IOException failure) {
            if (written) clean(reference);
            throw failure;
        } catch (LinkageError | RuntimeException nativeFailure) {
            if (written) clean(reference);
            throw new IOException("credential_write_failed");
        } finally { Arrays.fill(bytes, (byte) 0); }
    }
    @Override public synchronized Optional<char[]> read(String reference, CredentialTarget target) throws IOException {
        checkOpen();
        if (reference == null || !reference.startsWith("windows:")) return Optional.empty();
        PointerByReference value = new PointerByReference();
        try {
            if (!api.CredReadW(new WString(item(reference)), TYPE_GENERIC, 0, value)) {
                if (Native.getLastError() == NOT_FOUND) return Optional.empty();
                throw new IOException("credential_read_failed");
            }
            Credential credential = new Credential(value.getValue());
            if (credential.Type != TYPE_GENERIC || credential.CredentialBlobSize < 36
                    || credential.CredentialBlobSize > MAX_BLOB || credential.CredentialBlob == null)
                throw new IOException("invalid_credential_record");
            byte[] bytes = credential.CredentialBlob.getByteArray(0, credential.CredentialBlobSize);
            try {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                if (buffer.getInt() != 1) throw new IOException("invalid_credential_record");
                byte[] storedBinding = new byte[32]; buffer.get(storedBinding);
                if (!MessageDigest.isEqual(storedBinding, binding(target))) return Optional.empty();
                CharBuffer decoded = StandardCharsets.UTF_8.newDecoder().decode(buffer);
                char[] result = new char[decoded.remaining()]; decoded.get(result);
                if (decoded.hasArray()) Arrays.fill(decoded.array(), '\0');
                return Optional.of(result);
            } finally {
                Arrays.fill(bytes, (byte) 0);
                credential.CredentialBlob.clear(credential.CredentialBlobSize);
            }
        } catch (LinkageError | RuntimeException nativeFailure) { throw new IOException("credential_read_failed"); }
        finally { if (value.getValue() != null) api.CredFree(value.getValue()); }
    }
    @Override public synchronized void delete(String reference) throws IOException {
        checkOpen();
        if (reference == null) return;
        try {
            if (!api.CredDeleteW(new WString(item(reference)), TYPE_GENERIC, 0) && Native.getLastError() != NOT_FOUND)
                throw new IOException("credential_delete_failed");
        } catch (LinkageError | RuntimeException nativeFailure) { throw new IOException("credential_delete_failed"); }
    }
    private static String item(String reference) throws IOException {
        if (reference == null || !reference.matches("windows:[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))
            throw new IOException("invalid_credential_reference");
        return "SelfAnalyst/integrations/v1/" + reference.substring(8);
    }
    private static byte[] binding(CredentialTarget target) {
        try { return MessageDigest.getInstance("SHA-256").digest((target.connectionId() + "\n" + target.recipient())
                .getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private void checkOpen() throws IOException { if (closed) throw new IOException("credential_store_closed"); }
    private void clean(String reference) {
        try { delete(reference); cleanup.remove(reference); }
        catch (IOException | RuntimeException failure) { cleanup.add(reference); }
    }
    @Override public synchronized boolean cleanupPending() { return !cleanup.isEmpty(); }
    @Override public synchronized boolean retryCleanup() {
        for (String reference : Set.copyOf(cleanup)) clean(reference);
        return cleanup.isEmpty();
    }
    @Override public synchronized void close() { retryCleanup(); closed = true; }
}
