package com.selfanalyst.chatgpt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.channels.FileChannel;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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
    @EnabledOnOs(OS.WINDOWS)
    void createsAndReplacesWindowsObjectsWithCurrentUserOwnerAndExclusiveAcl() throws Exception {
        Path parent = root.resolve("new-parent");
        Path directory = parent.resolve("credentials");
        ChatGptCredentialStore store = new ChatGptCredentialStore(directory);
        store.locked(tx -> {
            tx.data().putObject("accounts").putObject("one").put("token", "first-secret");
            tx.save();
            return null;
        });
        assertWindowsProtection(parent, true);
        assertWindowsProtection(directory, true);
        assertWindowsProtection(directory.resolve(".lock"), false);
        assertWindowsProtection(directory.resolve("credentials.json"), false);
        store.refreshing(() -> null);
        assertWindowsProtection(directory.resolve(".refresh-lock"), false);
        store.locked(tx -> {
            tx.data().withObject("accounts").withObject("one").put("token", "rotated-secret");
            tx.save();
            return null;
        });
        assertWindowsProtection(directory.resolve("credentials.json"), false);
        assertEquals("rotated-secret", new ChatGptCredentialStore(directory).read()
                .path("accounts").path("one").path("token").asText());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void rejectsExtraWindowsAclEntriesWithoutRepairingExistingObjects() throws Exception {
        Path directory = root.resolve("credentials");
        ChatGptCredentialStore store = new ChatGptCredentialStore(directory);
        store.locked(tx -> null);
        Path credentials = directory.resolve("credentials.json");
        byte[] originalData = Files.readAllBytes(credentials);
        UserPrincipal other = otherWindowsPrincipal();
        for (Path path : List.of(directory, directory.resolve(".lock"), credentials)) {
            AclFileAttributeView acl = windowsAcl(path);
            List<AclEntry> original = acl.getAcl();
            var unsafe = new ArrayList<>(original);
            unsafe.add(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(other)
                    .setPermissions(AclEntryPermission.READ_DATA).build());
            acl.setAcl(unsafe);
            try {
                List<AclEntry> tampered = acl.getAcl();
                assertStorageFailure(store::read);
                assertStorageFailure(() -> store.locked(tx -> null));
                assertEquals(tampered, acl.getAcl());
                assertArrayEquals(originalData, Files.readAllBytes(credentials));
            } finally {
                acl.setAcl(original);
            }
        }
        assertWindowsProtection(directory, true);
        assertWindowsProtection(directory.resolve(".lock"), false);
        assertWindowsProtection(credentials, false);
    }

    @Test
    void rejectsForeignAclOwnerWithoutMutatingProtection() {
        assertForeignAclOwnerRejected(() -> "current-user", () -> "foreign-owner");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void rejectsForeignWindowsOwnersIncludingPreexistingEmptyObjects() throws Exception {
        UserPrincipal user = currentUser();
        // Elevated Windows creates objects owned by Administrators. Ordinary tokens cannot
        // assign that foreign owner; exercise the same read-only verifier in that case.
        UserPrincipal defaultOwner = windowsAcl(root).getOwner();
        if (defaultOwner.equals(user)) {
            assertForeignAclOwnerRejected(user, otherWindowsPrincipal());
            return;
        }
        Path directory = root.resolve("credentials");
        ChatGptCredentialStore store = new ChatGptCredentialStore(directory);
        store.locked(tx -> null);
        Path credentials = directory.resolve("credentials.json");
        byte[] originalData = Files.readAllBytes(credentials);
        for (Path path : List.of(directory, directory.resolve(".lock"), credentials)) {
            AclFileAttributeView acl = windowsAcl(path);
            List<AclEntry> originalAcl = acl.getAcl();
            acl.setOwner(defaultOwner);
            try {
                assertStorageFailure(store::read);
                assertStorageFailure(() -> store.locked(tx -> null));
                assertEquals(defaultOwner, acl.getOwner());
                assertEquals(originalAcl, acl.getAcl());
                assertArrayEquals(originalData, Files.readAllBytes(credentials));
            } finally {
                acl.setOwner(user);
            }
        }
        Path empty = Files.createDirectory(root.resolve("preexisting-empty"));
        AclFileAttributeView emptyAcl = windowsAcl(empty);
        emptyAcl.setAcl(windowsAcl(directory).getAcl());
        assertEquals(defaultOwner, emptyAcl.getOwner());
        ChatGptCredentialStore existingEmptyStore = new ChatGptCredentialStore(empty);
        assertStorageFailure(existingEmptyStore::read);
        assertStorageFailure(() -> existingEmptyStore.locked(tx -> null));
        assertEquals(defaultOwner, emptyAcl.getOwner());
        try (var entries = Files.list(empty)) { assertEquals(0, entries.count()); }
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
    void crossProcessRefreshWaitTimesOutWithoutBlockingReadsOrChangingCredentials() throws Exception {
        Path directory = root.resolve("credentials");
        ChatGptCredentialStore store = new ChatGptCredentialStore(directory, Duration.ofMillis(250));
        store.locked(tx -> null);
        store.refreshing(() -> null);
        byte[] original = Files.readAllBytes(directory.resolve("credentials.json"));
        Process holder = startRefreshHolder(directory);
        try {
            awaitRefreshHolder(holder);
            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> assertFalse(store.read().isEmpty()));
            AtomicBoolean invoked = new AtomicBoolean();
            assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
                IllegalStateException error = assertThrows(IllegalStateException.class,
                        () -> store.refreshing(() -> { invoked.set(true); return null; }));
                assertEquals("chatgpt_refresh_failed", error.getMessage());
                assertNull(error.getCause());
            });
            assertFalse(invoked.get());
            assertArrayEquals(original, Files.readAllBytes(directory.resolve("credentials.json")));
        } finally {
            holder.destroyForcibly().waitFor(15, TimeUnit.SECONDS);
        }
        assertEquals("ready", store.refreshing(() -> "ready"));
    }

    @Test
    void crossProcessRefreshWaitCanBeInterruptedWithoutKeepingLocalLock() throws Exception {
        Path directory = root.resolve("credentials");
        ChatGptCredentialStore store = new ChatGptCredentialStore(directory);
        store.locked(tx -> null);
        store.refreshing(() -> null);
        Process holder = startRefreshHolder(directory);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        CountDownLatch started = new CountDownLatch(1);
        Thread waiter = null;
        try {
            awaitRefreshHolder(holder);
            waiter = new Thread(() -> {
                started.countDown();
                try { store.refreshing(() -> { throw new AssertionError("refresh must not run"); }); }
                catch (Throwable error) { failure.set(error); interrupted.set(Thread.currentThread().isInterrupted()); }
            });
            waiter.start();
            assertTrue(started.await(3, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (waiter.isAlive() && waiter.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < deadline)
                Thread.sleep(10);
            assertEquals(Thread.State.TIMED_WAITING, waiter.getState());
            waiter.interrupt();
            waiter.join(3000);
            assertFalse(waiter.isAlive());
            assertInstanceOf(IllegalStateException.class, failure.get());
            assertEquals("chatgpt_request_interrupted", failure.get().getMessage());
            assertTrue(interrupted.get());
        } finally {
            holder.destroyForcibly().waitFor(15, TimeUnit.SECONDS);
            if (waiter != null) { waiter.interrupt(); waiter.join(3000); }
        }
        assertEquals("ready", store.refreshing(() -> "ready"));
    }

    private Process startRefreshHolder(Path directory) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), RefreshLockProbe.class.getName(),
                directory.resolve(".refresh-lock").toString(), root.resolve("refresh-ready").toString())
                .redirectErrorStream(true).redirectOutput(root.resolve("refresh-probe.log").toFile()).start();
    }

    private void awaitRefreshHolder(Process process) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!Files.exists(root.resolve("refresh-ready")) && process.isAlive() && System.nanoTime() < deadline)
            Thread.sleep(20);
        assertTrue(Files.exists(root.resolve("refresh-ready")), "child did not obtain refresh lock");
    }

    public static class RefreshLockProbe {
        public static void main(String[] args) throws Exception {
            try (var channel = FileChannel.open(Path.of(args[0]), StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                 var ignored = channel.lock()) {
                Files.writeString(Path.of(args[1]), "ready");
                System.in.read();
            }
        }
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

    private UserPrincipal currentUser() throws Exception {
        return root.getFileSystem().getUserPrincipalLookupService()
                .lookupPrincipalByName(System.getProperty("user.name"));
    }

    private void assertForeignAclOwnerRejected(UserPrincipal user, UserPrincipal other) {
        assertNotEquals(user, other);
        AclFileAttributeView acl = new AclFileAttributeView() {
            public String name() { return "acl"; }
            public UserPrincipal getOwner() { return other; }
            public List<AclEntry> getAcl() { throw new AssertionError("Foreign owner must fail before reading ACL"); }
            public void setOwner(UserPrincipal owner) { throw new AssertionError("Must not repair existing owner"); }
            public void setAcl(List<AclEntry> entries) { throw new AssertionError("Must not repair existing ACL"); }
        };
        assertStorageFailure(() -> ChatGptCredentialStore.verifyAclProtection(acl, user, true));
        assertStorageFailure(() -> ChatGptCredentialStore.verifyAclProtection(acl, user, false));
    }

    private AclFileAttributeView windowsAcl(Path path) {
        AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        assertNotNull(acl, "Windows credential storage requires ACL support");
        return acl;
    }

    private UserPrincipal otherWindowsPrincipal() throws Exception {
        UserPrincipal user = currentUser();
        for (Path path = root; path != null; path = path.getParent()) {
            for (AclEntry entry : windowsAcl(path).getAcl()) {
                if (!entry.principal().equals(user)) return entry.principal();
            }
        }
        throw new AssertionError("Windows test fixture has no foreign principal");
    }

    private void assertWindowsProtection(Path path, boolean directory) throws Exception {
        AclFileAttributeView acl = windowsAcl(path);
        UserPrincipal user = currentUser();
        assertEquals(user, acl.getOwner());
        List<AclEntry> entries = acl.getAcl();
        assertFalse(entries.isEmpty());
        assertTrue(entries.stream().allMatch(entry -> entry.type() == AclEntryType.ALLOW && entry.principal().equals(user)));
        assertTrue(entries.stream().anyMatch(entry -> entry.permissions().containsAll(EnumSet.allOf(AclEntryPermission.class))
                && (!directory || entry.flags().containsAll(Set.of(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT)))));
    }

    private void assertStorageFailure(org.junit.jupiter.api.function.Executable action) {
        IllegalStateException failure = assertThrows(IllegalStateException.class, action);
        assertEquals("chatgpt_storage_unavailable", failure.getMessage());
        assertNull(failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
    }
}
