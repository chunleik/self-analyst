package com.selfanalyst.integrations;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class MailActivationTransactionTest {
    @TempDir Path root;
    private static final String MODEL = "https://model.example.com/v1";
    private static IntegrationConfig.MailAccount account(String reference, boolean enabled) {
        return new IntegrationConfig.MailAccount("mail-one", "qq", "synthetic@qq.com", "Synthetic", enabled,
                "all", List.of("INBOX", "Projects/Nested"), true, reference,
                reference != null && reference.startsWith("windows:") ? "windows" : "session", enabled ? 1 : 0);
    }
    private static IntegrationConfig config(IntegrationConfig.MailAccount account, long revision) {
        return new IntegrationConfig(1, revision, List.of(), List.of(account), List.of());
    }
    private IntegrationStore initial(String reference, boolean enabled) throws Exception {
        var store = new IntegrationStore(root); store.save(0, config(account(reference, enabled), 0)); return store;
    }
    private static IntegrationConfig activate(IntegrationCredentialService service, long revision, String secret) throws IOException {
        return service.activateMail(revision, "mail-one", 1, MODEL, secret == null ? null : secret.toCharArray(), () -> MODEL).config();
    }
    @Test void newSecureCredentialVerifiedBeforeSingleActivationCommitAndPortableExportDropsAuthorization() throws Exception {
        try (var store = initial(null, false); var credentials = new TestSecureCredentialStore()) {
            var service = new IntegrationCredentialService(store, credentials);
            credentials.afterRead = () -> assertFalse(store.snapshot().mailAccounts().getFirst().enabled());
            var activated = activate(service, 1, "synthetic-code");
            var mail = activated.mailAccounts().getFirst();
            assertEquals(2, activated.revision()); assertTrue(mail.enabled()); assertEquals(1, mail.disclosureVersion());
            assertEquals("windows", mail.storageMode()); assertTrue(mail.credentialRef().startsWith("windows:"));
            assertEquals("all", mail.folderScope()); assertTrue(mail.bodyAccess());
            assertEquals(1, credentials.writes); assertEquals(1, credentials.reads);
            String disk = Files.readString(store.path()); assertFalse(disk.contains("synthetic-code"));
            var exported = IntegrationStore.parse(store.exportMetadata()).mailAccounts().getFirst();
            assertFalse(exported.enabled()); assertEquals(0, exported.disclosureVersion()); assertNull(exported.credentialRef());
            assertEquals("all", exported.folderScope());
        }
    }
    @Test void reuseOnlyReadableMatchingWindowsCredentialWithoutWritingOrDeletingIt() throws Exception {
        try (var credentials = new TestSecureCredentialStore()) {
            String reference = credentials.write(account(null, false).credentialTarget(), "synthetic-code".toCharArray(), "windows");
            try (var store = initial(reference, false)) {
                var service = new IntegrationCredentialService(store, credentials);
                assertEquals(reference, activate(service, 1, null).mailAccounts().getFirst().credentialRef());
                assertEquals(1, credentials.writes); assertEquals(0, credentials.deletes);
                assertEquals("revision_conflict", assertThrows(IOException.class, () -> activate(service, 1, "again")).getMessage());
                assertEquals(1, credentials.writes);
            }
        }
    }
    @Test void legacySessionRequiresReentryAndIsNeverAutomaticallyPersisted() throws Exception {
        try (var credentials = new TestSecureCredentialStore()) {
            String old = credentials.write(account(null, false).credentialTarget(), "synthetic-old".toCharArray(), "session");
            try (var store = initial(old, true)) {
                var service = new IntegrationCredentialService(store, credentials);
                byte[] disk = Files.readAllBytes(store.path());
                assertEquals("mail_credential_required", assertThrows(IOException.class, () -> activate(service, 1, null)).getMessage());
                assertEquals(0, credentials.reads); assertEquals(1, credentials.writes);
                assertArrayEquals(disk, Files.readAllBytes(store.path())); assertTrue(store.snapshot().mailAccounts().getFirst().enabled());
                credentials.beforeDelete = () -> {
                    var committed = store.snapshot().mailAccounts().getFirst();
                    assertTrue(committed.enabled()); assertNotEquals(old, committed.credentialRef());
                };
                activate(service, 1, "synthetic-reentered");
                assertFalse(credentials.entries.containsKey(old));
            }
        }
    }
    @Test void missingOrMismatchedConfirmationAndStaleRevisionHaveNoCredentialSideEffects() throws Exception {
        try (var store = initial(null, false); var credentials = new TestSecureCredentialStore()) {
            var service = new IntegrationCredentialService(store, credentials);
            assertEquals("mail_activation_confirmation_required", assertThrows(IOException.class,
                    () -> service.activateMail(1, "mail-one", 0, MODEL, "secret".toCharArray(), () -> MODEL)).getMessage());
            assertEquals("mail_activation_confirmation_required", assertThrows(IOException.class,
                    () -> service.activateMail(1, "mail-one", 1, " ", "secret".toCharArray(), () -> MODEL)).getMessage());
            assertEquals("model_target_unavailable", assertThrows(IOException.class,
                    () -> service.activateMail(1, "mail-one", 1, MODEL, "secret".toCharArray(), () -> null)).getMessage());
            assertEquals("model_target_changed", assertThrows(IOException.class,
                    () -> service.activateMail(1, "mail-one", 1, MODEL, "secret".toCharArray(), () -> MODEL + "/changed")).getMessage());
            assertEquals("revision_conflict", assertThrows(IOException.class, () -> activate(service, 0, "secret")).getMessage());
            assertEquals(0, credentials.writes); assertEquals(0, credentials.reads); assertEquals(1, store.snapshot().revision());
        }
    }
    @Test void failedWriteAndUnsupportedPlatformPreservePreviouslyEnabledStateAndNeverFallback() throws Exception {
        try (var credentials = new TestSecureCredentialStore()) {
            String old = credentials.write(account(null, false).credentialTarget(), "old".toCharArray(), "windows");
            try (var store = initial(old, true)) {
                var before = store.snapshot(); var service = new IntegrationCredentialService(store, credentials);
                for (String failure : List.of("persistent_credentials_unavailable", "credential_write_failed", "credential_verification_failed")) {
                    credentials.writeFailure = failure;
                    assertEquals(failure, assertThrows(IOException.class, () -> activate(service, 1, "new")).getMessage());
                    assertEquals(before, store.snapshot()); assertTrue(credentials.entries.containsKey(old));
                    assertEquals(1, credentials.entries.size());
                }
            }
        }
    }
    @Test void readbackMismatchMissingOrFailureCleansNewCredentialAndNeverEnables() throws Exception {
        try (var store = initial(null, false); var credentials = new TestSecureCredentialStore()) {
            var service = new IntegrationCredentialService(store, credentials);
            credentials.wrongRead = true;
            assertEquals("credential_verification_failed", assertThrows(IOException.class, () -> activate(service, 1, "new")).getMessage());
            assertTrue(credentials.entries.isEmpty());
            credentials.wrongRead = false; credentials.missingRead = true;
            assertEquals("credential_verification_failed", assertThrows(IOException.class, () -> activate(service, 1, "new")).getMessage());
            assertTrue(credentials.entries.isEmpty());
            credentials.missingRead = false; credentials.readFailure = "credential_read_failed";
            assertEquals("credential_read_failed", assertThrows(IOException.class, () -> activate(service, 1, "new")).getMessage());
            assertTrue(credentials.entries.isEmpty()); assertFalse(store.snapshot().mailAccounts().getFirst().enabled());
            assertEquals(1, store.snapshot().revision());
        }
    }
    @Test void rollbackCleanupFailureIsVisibleAndRetryableWithoutEnabling() throws Exception {
        try (var store = initial(null, false); var credentials = new TestSecureCredentialStore()) {
            var service = new IntegrationCredentialService(store, credentials);
            credentials.wrongRead = true; credentials.failDelete = true;
            assertThrows(IOException.class, () -> activate(service, 1, "new"));
            assertTrue(service.cleanupPending()); assertFalse(store.snapshot().mailAccounts().getFirst().enabled());
            credentials.failDelete = false;
            assertTrue(service.retryCleanup()); assertFalse(service.cleanupPending()); assertTrue(credentials.entries.isEmpty());
        }
    }
    @Test void failedAtomicCommitPreservesOldCredentialAndAuthorization() throws Exception {
        try (var credentials = new TestSecureCredentialStore()) {
            String old = credentials.write(account(null, false).credentialTarget(), "old".toCharArray(), "windows");
            try (var normal = initial(old, true); var failing = new IntegrationStore(root, (from, to) -> { throw new IOException("synthetic_commit_failure"); })) {
                var before = normal.snapshot(); var service = new IntegrationCredentialService(failing, credentials);
                assertThrows(IOException.class, () -> activate(service, 1, "new"));
                assertEquals(before, normal.snapshot()); assertEquals(Set.of(old), credentials.entries.keySet());
            }
        }
    }
    @Test void atomicMoveThenExceptionIsRecognizedAsCommittedAndNeverDeletesActiveCredential() throws Exception {
        try (var credentials = new TestSecureCredentialStore()) {
            String old = credentials.write(account(null, false).credentialTarget(), "old".toCharArray(), "windows");
            try (var normal = initial(old, true); var uncertainStore = new IntegrationStore(root, (from, to) -> {
                Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                throw new IOException("synthetic_post_commit_failure");
            })) {
                var service = new IntegrationCredentialService(uncertainStore, credentials);
                var result = activate(service, 1, "new");
                String reference = result.mailAccounts().getFirst().credentialRef();
                assertEquals(result, normal.snapshot()); assertTrue(result.mailAccounts().getFirst().enabled());
                assertEquals(Set.of(reference), credentials.entries.keySet()); assertFalse(service.cleanupPending());
            }
        }
    }
    @Test void unreadableAfterPossibleCommitPreservesPossibleActiveCredentialUntilReconciled() throws Exception {
        try (var credentials = new TestSecureCredentialStore(); var normal = initial(null, false)) {
            var committedBytes = new AtomicReference<byte[]>();
            try (var uncertainStore = new IntegrationStore(root, (from, to) -> {
                Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                committedBytes.set(Files.readAllBytes(to));
                Files.writeString(to, "synthetic-unreadable-configuration");
                throw new IOException("synthetic_post_commit_failure");
            })) {
                var service = new IntegrationCredentialService(uncertainStore, credentials);
                assertEquals("mail_activation_outcome_uncertain", assertThrows(IOException.class,
                        () -> activate(service, 1, "new")).getMessage());
                String reference = credentials.lastWritten;
                assertEquals(Set.of(reference), credentials.entries.keySet()); assertFalse(service.cleanupPending());
                assertFalse(service.retryCleanup()); assertEquals(Set.of(reference), credentials.entries.keySet());
                Files.write(normal.path(), committedBytes.get());
                assertTrue(normal.snapshot().mailAccounts().getFirst().enabled());
                assertTrue(service.retryCleanup()); assertEquals(Set.of(reference), credentials.entries.keySet());
                assertFalse(service.cleanupPending());
            }
        }
    }
    @Test void reuseCommitIsVerifiedAfterMoveErrorAndRetainedWhenOutcomeCannotBeRead() throws Exception {
        for (boolean unreadable : List.of(false, true)) {
            Path scenario = root.resolve(unreadable ? "unreadable" : "verified");
            try (var credentials = new TestSecureCredentialStore(); var normal = new IntegrationStore(scenario)) {
                String reference = credentials.write(account(null, false).credentialTarget(), "old".toCharArray(), "windows");
                normal.save(0, config(account(reference, false), 0));
                var committedBytes = new AtomicReference<byte[]>();
                try (var uncertainStore = new IntegrationStore(scenario, (from, to) -> {
                    Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    committedBytes.set(Files.readAllBytes(to));
                    if (unreadable) Files.writeString(to, "synthetic-unreadable-configuration");
                    throw new IOException("synthetic_post_commit_failure");
                })) {
                    var service = new IntegrationCredentialService(uncertainStore, credentials);
                    if (unreadable) {
                        assertEquals("mail_activation_outcome_uncertain", assertThrows(IOException.class,
                                () -> activate(service, 1, null)).getMessage());
                        assertFalse(service.cleanupPending()); assertFalse(service.retryCleanup());
                        Files.write(normal.path(), committedBytes.get());
                        assertTrue(service.retryCleanup());
                    } else {
                        var result = activate(service, 1, null);
                        assertEquals(normal.snapshot(), result); assertTrue(result.mailAccounts().getFirst().enabled());
                    }
                    assertEquals(Set.of(reference), credentials.entries.keySet());
                    assertEquals(1, credentials.writes); assertEquals(0, credentials.deletes);
                }
            }
        }
    }
    @Test void targetIsCheckedAgainImmediatelyBeforeCredentialWrite() throws Exception {
        try (var store = initial(null, false); var credentials = new TestSecureCredentialStore()) {
            var service = new IntegrationCredentialService(store, credentials);
            var checks = new java.util.concurrent.atomic.AtomicInteger();
            assertEquals("model_target_changed", assertThrows(IOException.class,
                    () -> service.activateMail(1, "mail-one", 1, MODEL, "new".toCharArray(),
                            () -> checks.incrementAndGet() == 1 ? MODEL : MODEL + "/changed")).getMessage());
            assertEquals(0, credentials.writes); assertEquals(1, store.snapshot().revision());
        }
    }
    @Test void concurrentDisconnectWinsCasAndNewCredentialIsRemoved() throws Exception {
        try (var store = initial(null, false); var credentials = new TestSecureCredentialStore()) {
            var service = new IntegrationCredentialService(store, credentials);
            credentials.afterWrite = () -> new IntegrationCredentialService(store, credentials).disconnect(1, "mail-one");
            assertEquals("revision_conflict", assertThrows(IOException.class, () -> activate(service, 1, "new")).getMessage());
            assertEquals(2, store.snapshot().revision()); assertFalse(store.snapshot().mailAccounts().getFirst().enabled());
            assertNull(store.snapshot().mailAccounts().getFirst().credentialRef()); assertTrue(credentials.entries.isEmpty());
        }
    }
    @Test void targetChangeDuringWriteOrReuseBlocksCommitAndCleansOnlyNewCredential() throws Exception {
        try (var store = initial(null, false); var credentials = new TestSecureCredentialStore()) {
            var service = new IntegrationCredentialService(store, credentials);
            var target = new AtomicReference<>(MODEL);
            credentials.afterWrite = () -> target.set(MODEL + "/changed");
            assertEquals("model_target_changed", assertThrows(IOException.class,
                    () -> service.activateMail(1, "mail-one", 1, MODEL, "new".toCharArray(), target::get)).getMessage());
            assertTrue(credentials.entries.isEmpty()); assertFalse(store.snapshot().mailAccounts().getFirst().enabled());
            credentials.afterWrite = null; target.set(MODEL);
            var activated = activate(service, 1, "new"); String reference = activated.mailAccounts().getFirst().credentialRef();
            store.save(2, config(activated.mailAccounts().getFirst().awaitingActivation(), 2));
            credentials.afterRead = () -> target.set(MODEL + "/changed");
            assertEquals("model_target_changed", assertThrows(IOException.class,
                    () -> service.activateMail(3, "mail-one", 1, MODEL, null, target::get)).getMessage());
            assertEquals(Set.of(reference), credentials.entries.keySet()); assertEquals(3, store.snapshot().revision());
            assertFalse(store.snapshot().mailAccounts().getFirst().enabled());
        }
    }
    @Test void staleWindowsReferenceCannotBeReusedForAnotherAccountTarget() throws Exception {
        try (var credentials = new TestSecureCredentialStore()) {
            String reference = credentials.write(CredentialTarget.mail("mail-one", "qq", "other@qq.com"), "old".toCharArray(), "windows");
            try (var store = initial(reference, false)) {
                assertEquals("mail_credential_required", assertThrows(IOException.class,
                        () -> activate(new IntegrationCredentialService(store, credentials), 1, null)).getMessage());
                assertEquals(1, credentials.writes); assertFalse(store.snapshot().mailAccounts().getFirst().enabled());
            }
        }
    }
    @Test void oldCredentialCleanupFailureDoesNotUndoSuccessfulActivationOrDeleteNewReference() throws Exception {
        try (var credentials = new TestSecureCredentialStore()) {
            String old = credentials.write(account(null, false).credentialTarget(), "old".toCharArray(), "windows");
            try (var store = initial(old, true)) {
                var service = new IntegrationCredentialService(store, credentials); credentials.failDelete = true;
                var result = activate(service, 1, "new");
                String current = result.mailAccounts().getFirst().credentialRef();
                assertTrue(result.mailAccounts().getFirst().enabled()); assertNotEquals(old, current); assertTrue(service.cleanupPending());
                credentials.failDelete = false; assertTrue(service.retryCleanup());
                assertEquals(Set.of(current), credentials.entries.keySet());
                service.disconnect(2, "mail-one");
                assertFalse(store.snapshot().mailAccounts().getFirst().enabled()); assertTrue(credentials.entries.isEmpty());
            }
        }
    }
}
