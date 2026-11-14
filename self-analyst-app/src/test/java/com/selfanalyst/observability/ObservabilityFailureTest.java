package com.selfanalyst.observability;

import static org.junit.jupiter.api.Assertions.*;

import com.selfanalyst.integrations.*;

import io.opentelemetry.context.Context;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

class ObservabilityFailureTest {
    @TempDir Path root;
    @org.junit.jupiter.api.BeforeEach void canonicalTemporaryRoot() throws Exception {
        root = root.toRealPath();
    }

    static class Vault implements CredentialStore {
        record Entry(CredentialTarget target, char[] secret) {}

        final Map<String, Entry> entries = new HashMap<>();
        boolean failWrite, failDelete;
        Runnable afterWrite = () -> {};

        public String write(CredentialTarget target, char[] secret, String mode)
                throws IOException {
            if (failWrite) throw new IOException("synthetic_vault_failure");
            String ref = "windows:" + UUID.randomUUID();
            entries.put(ref, new Entry(target, secret.clone()));
            afterWrite.run();
            return ref;
        }

        public Optional<char[]> read(String ref, CredentialTarget target) {
            Entry entry = entries.get(ref);
            return entry != null && entry.target().equals(target)
                    ? Optional.of(entry.secret().clone())
                    : Optional.empty();
        }

        public void delete(String ref) throws IOException {
            if (failDelete) throw new IOException("synthetic_delete_failure");
            entries.remove(ref);
        }

        public void close() {}
    }

    ObservabilityConfig config(String project) {
        return new ObservabilityConfig(
                1,
                0,
                "http://127.0.0.1:12345",
                project,
                1,
                "metadata",
                Set.of(),
                Set.of(),
                Set.of(),
                "all-runtime",
                "",
                "");
    }

    ObservabilityService service(Vault vault, ObservabilityTransport transport) {
        return new ObservabilityService(
                root, vault, true, new ObservabilityEndpointPolicy(), transport);
    }

    @Test
    void failedVaultWritePreservesPreviousCredential() throws Exception {
        Vault vault = new Vault();
        try (var service = service(vault, null)) {
            service.save(0, config("project-a"));
            service.credential(1, "old-synthetic".toCharArray());
            String old = vault.entries.keySet().iterator().next();
            vault.failWrite = true;
            assertThrows(
                    IOException.class, () -> service.credential(2, "new-synthetic".toCharArray()));
            assertEquals(Set.of(old), vault.entries.keySet());
            assertEquals(2, ((ObservabilityConfig) service.snapshot().get("config")).revision());
            assertEquals(true, service.snapshot().get("credentialConfigured"));
        }
    }

    @Test
    void failedConfigurationCommitDeletesNewReferenceKeepsOld() throws Exception {
        Vault vault = new Vault();
        try (var service = service(vault, null)) {
            service.save(0, config("project-a"));
            service.credential(1, "old-synthetic".toCharArray());
            String old = vault.entries.keySet().iterator().next();
            // A directory in place of the advisory lock deterministically fails the commit on all
            // platforms.
            vault.afterWrite =
                    () -> {
                        try {
                            Files.delete(root.resolve("config/.observability.lock"));
                            Files.createDirectory(root.resolve("config/.observability.lock"));
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    };
            assertThrows(
                    IOException.class, () -> service.credential(2, "new-synthetic".toCharArray()));
            assertEquals(Set.of(old), vault.entries.keySet());
            assertEquals(true, service.snapshot().get("credentialConfigured"));
        }
    }

    @Test
    void cleanupFailureIsVisibleAndRetriedAndProjectChangeCannotReuseSecret() throws Exception {
        Vault vault = new Vault();
        try (var service = service(vault, null)) {
            service.save(0, config("project-a"));
            service.credential(1, "old-synthetic".toCharArray());
            vault.failDelete = true;
            service.save(2, config("project-b").revision(2));
            assertEquals(true, service.snapshot().get("credentialCleanupPending"));
            assertEquals(false, service.snapshot().get("credentialConfigured"));
            String token = (String) service.preview(3).get("confirmationToken");
            assertThrows(IOException.class, () -> service.enable(3, 1, token));
            vault.failDelete = false;
            service.credential(3, "new-synthetic".toCharArray());
            assertEquals(false, service.snapshot().get("credentialCleanupPending"));
            assertEquals(1, vault.entries.size());
            var entry = vault.entries.values().iterator().next();
            assertTrue(entry.target().toString().contains("project-b"));
        }
    }

    @Test
    void queueIsBoundedAndRepeatedServerFailureIsFinite() throws Exception {
        Vault vault = new Vault();
        AtomicInteger calls = new AtomicInteger();
        var policy = new ObservabilityEndpointPolicy();
        var transport =
                new ObservabilityTransport(policy) {
                    @Override
                    public int send(
                            ObservabilityEndpointPolicy.ApprovedEndpoint ep,
                            String auth,
                            byte[] body,
                            java.util.function.BooleanSupplier live) {
                        calls.incrementAndGet();
                        return calls.get() % 2 == 0 ? 429 : 503;
                    }
                };
        try (var service = service(vault, transport)) {
            service.save(0, config("project-a"));
            service.credential(1, "synthetic".toCharArray());
            service.enable(2, 1, (String) service.preview(2).get("confirmationToken"));
            // Disable the scheduler to inspect deterministic bounded queue and explicit drain
            // behavior.
            var field = ObservabilityService.class.getDeclaredField("worker");
            field.setAccessible(true);
            ((ScheduledExecutorService) field.get(service)).shutdownNow();
            assertTimeoutPreemptively(
                    java.time.Duration.ofSeconds(3),
                    () -> {
                        for (int i = 0; i < 2200; i++)
                            service.begin("model.generation", "s", Context.root())
                                    .finish("success", 1, 1, "actual", null);
                    });
            var status = (Map<?, ?>) service.snapshot().get("status");
            assertTrue(((Number) status.get("dropped")).longValue() >= 152);
            service.drain();
            assertEquals(3, calls.get());
            assertEquals(128L, ((Map<?, ?>) service.snapshot().get("status")).get("failed"));
        } finally {
            policy.close();
        }
    }
}
