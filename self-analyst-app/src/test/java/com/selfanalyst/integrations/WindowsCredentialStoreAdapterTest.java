package com.selfanalyst.integrations;

import com.sun.jna.*;
import com.sun.jna.ptr.PointerByReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the real adapter's native API boundary with synthetic records on every platform. */
class WindowsCredentialStoreAdapterTest {
    @TempDir Path root;
    private static final CredentialTarget TARGET = CredentialTarget.mail("mail-one", "qq", "synthetic@qq.com");
    @Test void realAdapterVerifiesSecureWriteBindingAndDeletesTemporarySyntheticRecord() throws Exception {
        try (var vault = new FakeVault(); var store = new WindowsCredentialStore(vault)) {
            String reference = store.write(TARGET, "synthetic-code".toCharArray(), "windows");
            assertEquals(1, vault.writes); assertEquals(1, vault.reads); assertEquals(2, vault.persist);
            assertArrayEquals("synthetic-code".toCharArray(), store.read(reference, TARGET).orElseThrow());
            assertTrue(store.read(reference, CredentialTarget.mail("mail-one", "qq", "other@qq.com")).isEmpty());
            store.delete(reference); assertTrue(vault.records.isEmpty()); assertFalse(store.cleanupPending());
        }
    }
    @Test void readbackFailureAndDeleteFailureStayVisibleThroughServiceAndCanBeRetried() throws Exception {
        try (var vault = new FakeVault(); var credentials = new WindowsCredentialStore(vault); var store = new IntegrationStore(root)) {
            var account = new IntegrationConfig.MailAccount("mail-one", "qq", "synthetic@qq.com", "Synthetic", false,
                    "all", List.of(), false, null, "session", 0);
            store.save(0, new IntegrationConfig(1, 0, List.of(), List.of(account), List.of()));
            var service = new IntegrationCredentialService(store, credentials);
            vault.failRead = true; vault.failDelete = true;
            var error = assertThrows(IOException.class, () -> service.activateMail(1, "mail-one", 1, "model", "new".toCharArray(), () -> "model"));
            assertEquals("credential_read_failed", error.getMessage());
            assertTrue(credentials.cleanupPending()); assertTrue(service.cleanupPending()); assertEquals(1, vault.records.size());
            assertEquals(1, store.snapshot().revision()); assertFalse(store.snapshot().mailAccounts().getFirst().enabled());
            vault.failDelete = false; assertTrue(service.retryCleanup()); assertTrue(vault.records.isEmpty()); assertFalse(service.cleanupPending());
        }
    }
    @Test void mismatchedReadbackIsRemovedAndFailedWriteNeverReportsPersistence() throws Exception {
        try (var vault = new FakeVault(); var store = new WindowsCredentialStore(vault)) {
            vault.corruptRead = true;
            assertEquals("credential_verification_failed", assertThrows(IOException.class,
                    () -> store.write(TARGET, "synthetic-code".toCharArray(), "windows")).getMessage());
            assertTrue(vault.records.isEmpty()); assertFalse(store.cleanupPending());
            vault.failWrite = true;
            assertEquals("credential_write_failed", assertThrows(IOException.class,
                    () -> store.write(TARGET, "synthetic-code".toCharArray(), "windows")).getMessage());
            assertTrue(vault.records.isEmpty());
        }
    }
    private static final class FakeVault implements WindowsCredentialStore.Vault, AutoCloseable {
        final Map<String, byte[]> records = new HashMap<>();
        final Map<Long, Allocation> allocations = new HashMap<>();
        int writes, reads, persist;
        boolean failWrite, failRead, failDelete, corruptRead;
        private record Allocation(WindowsCredentialStore.Credential credential, Memory blob) {}
        @Override public boolean CredWriteW(WindowsCredentialStore.Credential value, int flags) {
            writes++; persist = value.Persist;
            if (failWrite) { Native.setLastError(5); return false; }
            records.put(value.TargetName.toString(), value.CredentialBlob.getByteArray(0, value.CredentialBlobSize));
            return true;
        }
        @Override public boolean CredReadW(WString target, int type, int flags, PointerByReference value) {
            reads++;
            if (failRead) { Native.setLastError(5); return false; }
            byte[] data = records.get(target.toString());
            if (data == null) { Native.setLastError(1168); return false; }
            var blob = new Memory(data.length); blob.write(0, data, 0, data.length);
            if (corruptRead) blob.setByte(data.length - 1, (byte)'x');
            var credential = new WindowsCredentialStore.Credential();
            credential.Type = 1; credential.CredentialBlobSize = data.length; credential.CredentialBlob = blob;
            credential.write();
            allocations.put(Pointer.nativeValue(credential.getPointer()), new Allocation(credential, blob));
            value.setValue(credential.getPointer()); return true;
        }
        @Override public boolean CredDeleteW(WString target, int type, int flags) {
            if (failDelete) { Native.setLastError(5); return false; }
            byte[] removed = records.remove(target.toString());
            if (removed == null) { Native.setLastError(1168); return false; }
            Arrays.fill(removed, (byte)0); return true;
        }
        @Override public void CredFree(Pointer buffer) {
            var allocation = allocations.remove(Pointer.nativeValue(buffer));
            if (allocation != null) allocation.blob().close();
        }
        @Override public void close() {
            records.values().forEach(value -> Arrays.fill(value, (byte)0)); records.clear();
            allocations.values().forEach(value -> value.blob().close()); allocations.clear();
        }
    }
}
