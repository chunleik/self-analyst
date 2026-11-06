package com.selfanalyst.chatgpt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ChatGptCredentialStoreTest {
    @TempDir Path root;

    @Test
    void constructingAndReadingAbsentStoreDoesNotWrite() throws Exception {
        Path directory = root.resolve("missing").resolve("credentials");
        ChatGptCredentialStore store = new ChatGptCredentialStore(directory);
        assertTrue(store.read().isEmpty());
        assertFalse(Files.exists(root.resolve("missing")));
    }

    @Test
    void createsProtectedFilesAndStableHostIdentity() throws Exception {
        Path directory = root.resolve("credentials");
        ChatGptCredentialStore store = new ChatGptCredentialStore(directory);
        String hostId = store.locked(tx -> tx.data().path("hostId").asText());
        assertTrue(hostId.startsWith("urn:uuid:"));
        assertTrue(store.read().path("accounts").isObject());
        assertEquals("", store.read().path("activeAccountId").asText());
        assertEquals(hostId, new ChatGptCredentialStore(directory).read().path("hostId").asText());
        if (Files.getFileAttributeView(directory, PosixFileAttributeView.class) != null) {
            assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(directory));
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(directory.resolve("credentials.json")));
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(directory.resolve(".lock")));
        }
    }

    @Test
    void explicitSavePersistsAtomicallyAndUnsavedChangesAreDiscarded() throws Exception {
        Path directory = root.resolve("credentials");
        ChatGptCredentialStore store = new ChatGptCredentialStore(directory);
        store.locked(tx -> { tx.data().putObject("accounts").putObject("one").put("token", "first-secret"); tx.save(); return null; });
        store.locked(tx -> { tx.data().withObject("accounts").withObject("one").put("token", "second-secret"); return null; });
        assertEquals("first-secret", store.read().path("accounts").path("one").path("token").asText());
        store.locked(tx -> { tx.data().withObject("accounts").withObject("one").put("token", "rotated-secret"); tx.save(); return null; });
        assertEquals("rotated-secret", new ChatGptCredentialStore(directory).read().path("accounts").path("one").path("token").asText());
        try (var files = Files.list(directory)) { assertEquals(2, files.count()); }
    }

    @Test
    void serializesTransactionsAcrossStoreInstances() throws Exception {
        Path directory = root.resolve("credentials");
        try (var workers = Executors.newFixedThreadPool(4)) {
            var tasks = new ArrayList<Callable<Void>>();
            for (int i = 0; i < 24; i++) tasks.add(() -> new ChatGptCredentialStore(directory).locked(tx -> {
                tx.data().put("count", tx.data().path("count").asInt() + 1);
                tx.save();
                return null;
            }));
            for (var result : workers.invokeAll(tasks)) result.get();
        }
        assertEquals(24, new ChatGptCredentialStore(directory).read().path("count").asInt());
    }

    @Test
    void rejectsUnsafeDirectoryAndFilePermissions() throws Exception {
        assumePosix();
        Path directory = root.resolve("credentials");
        ChatGptCredentialStore store = new ChatGptCredentialStore(directory);
        store.locked(tx -> null);
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwxr-xr-x"));
        assertStorageFailure(store::read);
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        Files.setPosixFilePermissions(directory.resolve("credentials.json"), PosixFilePermissions.fromString("rw-r--r--"));
        assertStorageFailure(store::read);
        assertStorageFailure(() -> store.locked(tx -> null));
    }

    @Test
    void rejectsSymlinkComponentsAndCredentialFiles() throws Exception {
        assumePosix();
        Path actual = Files.createDirectory(root.resolve("actual"));
        Path alias = Files.createSymbolicLink(root.resolve("alias"), actual);
        assertStorageFailure(() -> new ChatGptCredentialStore(alias.resolve("new")).read());
        assertStorageFailure(() -> new ChatGptCredentialStore(alias.resolve("new")).locked(tx -> null));
        assertFalse(Files.exists(actual.resolve("new")));
        Path directory = root.resolve("credentials");
        ChatGptCredentialStore store = new ChatGptCredentialStore(directory);
        store.locked(tx -> null);
        Path target = directory.resolve("credentials.json");
        Path original = root.resolve("original.json");
        Files.move(target, original);
        Files.createSymbolicLink(target, original);
        assertStorageFailure(store::read);
        assertStorageFailure(() -> store.locked(tx -> null));
    }

    @Test
    void rejectsHardlinkedFiles() throws Exception {
        assumePosix();
        assumeTrue(root.getFileSystem().supportedFileAttributeViews().contains("unix"));
        Path directory = root.resolve("credentials");
        ChatGptCredentialStore store = new ChatGptCredentialStore(directory);
        store.locked(tx -> null);
        Files.createLink(root.resolve("copy"), directory.resolve("credentials.json"));
        assertStorageFailure(store::read);
    }

    @Test
    void corruptDataFailsWithoutExposingContentOrCreatingAnotherIdentity() throws Exception {
        Path directory = root.resolve("credentials");
        ChatGptCredentialStore store = new ChatGptCredentialStore(directory);
        store.locked(tx -> null);
        Files.writeString(directory.resolve("credentials.json"), "{secret-invalid-token");
        assertStorageFailure(store::read);
        assertStorageFailure(() -> store.locked(tx -> null));
        assertEquals("{secret-invalid-token", Files.readString(directory.resolve("credentials.json")));
    }

    @Test
    void readDoesNotRecreateMissingLockAndExpiredTransactionCannotSave() throws Exception {
        Path directory = root.resolve("credentials");
        ChatGptCredentialStore store = new ChatGptCredentialStore(directory);
        ChatGptCredentialStore.Transaction transaction = store.locked(tx -> tx);
        assertStorageFailure(transaction::save);
        Files.delete(directory.resolve(".lock"));
        assertStorageFailure(store::read);
        assertFalse(Files.exists(directory.resolve(".lock")));
    }

    @Test
    void preservesCallbackFailureWithoutSavingChanges() {
        ChatGptCredentialStore store = new ChatGptCredentialStore(root.resolve("credentials"));
        IllegalArgumentException expected = new IllegalArgumentException("domain_failure");
        assertSame(expected, assertThrows(IllegalArgumentException.class, () -> store.locked(tx -> { throw expected; })));
    }

    private void assumePosix() {
        assumeTrue(Files.getFileAttributeView(root, PosixFileAttributeView.class) != null);
    }

    private void assertStorageFailure(org.junit.jupiter.api.function.Executable action) {
        IllegalStateException failure = assertThrows(IllegalStateException.class, action);
        assertEquals("chatgpt_storage_unavailable", failure.getMessage());
        assertNull(failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
    }
}
