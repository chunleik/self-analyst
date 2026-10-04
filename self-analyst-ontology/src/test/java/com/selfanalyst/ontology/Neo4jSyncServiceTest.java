package com.selfanalyst.ontology;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.neo4j.driver.exceptions.AuthenticationException;
import org.neo4j.driver.exceptions.ServiceUnavailableException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static com.selfanalyst.ontology.Ontology.*;
import static org.junit.jupiter.api.Assertions.*;

class Neo4jSyncServiceTest {
    @TempDir Path root;
    private Neo4jSyncConfig valid() {
        return new Neo4jSyncConfig(true, "bolt://127.0.0.1:7687", "neo4j", "synthetic-user", "TEST_PASSWORD", "synthetic-graph", 2);
    }
    private OntologyService ontology() { return new OntologyService(new OntologyStore(root.resolve("ontology.db")), Snapshot::empty); }
    private Neo4jSyncService service(OntologyService ontology, Neo4jSyncConfig config, String password, Neo4jSyncService.Writer writer) {
        return new Neo4jSyncService(ontology, () -> config, ignored -> password, writer);
    }
    @Test void disabledStatusConstructionAndMissingConfirmationNeverWrite() {
        AtomicInteger calls = new AtomicInteger();
        try (var ontology = ontology()) {
            var disabled = service(ontology, Neo4jSyncConfig.disabled(), "test-secret", (c, p, s) -> calls.incrementAndGet());
            assertEquals("disabled", disabled.status().get("state"));
            assertEquals("neo4j.disabled", assertThrows(IllegalStateException.class, () -> disabled.sync("anything")).getMessage());
            var enabled = service(ontology, valid(), "test-secret", (c, p, s) -> calls.incrementAndGet());
            assertEquals("ready", enabled.status().get("state"));
            assertEquals("neo4j.confirmationRequired", assertThrows(IllegalArgumentException.class, () -> enabled.sync("stale-target")).getMessage());
            assertEquals(0, calls.get());
        }
    }
    @Test void missingPasswordAndInvalidConfigStayLocal() {
        AtomicInteger calls = new AtomicInteger();
        try (var ontology = ontology()) {
            var missing = service(ontology, valid(), "", (c, p, s) -> calls.incrementAndGet());
            assertEquals("missingPassword", missing.status().get("state"));
            assertEquals("neo4j.missingPassword", assertThrows(IllegalStateException.class, () -> missing.sync(valid().fingerprint())).getMessage());
            var invalidConfig = new Neo4jSyncConfig(true, "bolt://neo4j:secret@localhost", "neo4j", "neo4j", "ENV", "unit", 3);
            var invalid = service(ontology, invalidConfig, "secret", (c, p, s) -> calls.incrementAndGet());
            assertEquals("invalidConfig", invalid.status().get("state"));
            assertFalse(invalid.status().toString().contains("secret"));
            assertThrows(IllegalArgumentException.class, () -> invalid.sync(invalidConfig.fingerprint()));
            assertEquals(0, calls.get());
        }
    }
    @Test void configurationChangesInvalidateConfirmationAndOldResults() {
        AtomicReference<Neo4jSyncConfig> config = new AtomicReference<>(valid());
        AtomicInteger calls = new AtomicInteger();
        try (var ontology = ontology()) {
            var service = new Neo4jSyncService(ontology, config::get, ignored -> "secret", (c, p, s) -> calls.incrementAndGet());
            String confirmation = config.get().fingerprint();
            assertEquals(true, service.sync(confirmation).get("success"));
            assertEquals("success", service.status().get("state"));
            config.set(new Neo4jSyncConfig(true, "bolt+s://other.example.com:7687", "other", "user", "ENV", "other", 3));
            assertThrows(IllegalArgumentException.class, () -> service.sync(confirmation));
            assertEquals(Map.of(), service.status().get("lastResult"));
            assertEquals(1, calls.get());
        }
    }
    @Test void errorsAreClassifiedAndNeverExposeDriverMessagesOrPassword() {
        for (RuntimeException failure : List.of(new AuthenticationException("Neo.ClientError.Security.Unauthorized", "private password and URI"),
                new ServiceUnavailableException("private query content"), new IllegalStateException("secret payload"))) {
            try (var ontology = ontology()) {
                var service = service(ontology, valid(), "synthetic-password", (c, p, s) -> { throw failure; });
                var error = assertThrows(IllegalStateException.class, () -> service.sync(valid().fingerprint()));
                assertNull(error.getCause());
                assertTrue(Set.of("neo4j.authenticationFailed", "neo4j.connectionFailed", "neo4j.syncFailed").contains(error.getMessage()));
                assertFalse(service.status().toString().contains("private"));
                assertFalse(service.status().toString().contains("synthetic-password"));
                assertEquals("error", service.status().get("state"));
                assertDoesNotThrow(ontology::status);
            }
        }
    }
    @Test void concurrentClickIsRejectedAndRunningFlagResets() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        try (var ontology = ontology(); var executor = Executors.newSingleThreadExecutor()) {
            var service = service(ontology, valid(), "secret", (c, p, s) -> {
                calls.incrementAndGet(); entered.countDown();
                try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(); }
            });
            Future<?> first = executor.submit(() -> service.sync(valid().fingerprint()));
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                assertEquals("running", service.status().get("state"));
                assertEquals("neo4j.busy", assertThrows(IllegalStateException.class, () -> service.sync(valid().fingerprint())).getMessage());
            } finally { release.countDown(); }
            first.get(10, TimeUnit.SECONDS);
            assertEquals(1, calls.get());
            assertEquals("success", service.status().get("state"));
        }
    }
    @Test void payloadBudgetRejectsWithoutSilentlyTruncating() {
        List<Entity> entities = new ArrayList<>();
        List<Evidence> evidence = java.util.stream.IntStream.range(0, 12)
                .mapToObj(i -> new Evidence("e:" + i, "字".repeat(800), true)).toList();
        for (int i = 0; i < 1000; i++) entities.add(new Entity("manual:" + i, "project", "Synthetic", "", List.of(), "manual", "", null, null, evidence, Map.of()));
        var snapshot = new ExportSnapshot(entities, List.of(), Map.of(), Map.of());
        assertEquals("neo4j.snapshotTooLarge", assertThrows(IllegalArgumentException.class, () -> Neo4jSnapshotWriter.prepare(snapshot)).getMessage());
    }
    @Test void unavailableSourceDoesNotPublishPreviouslyCachedData() {
        AtomicReference<Snapshot> source = new AtomicReference<>(Snapshot.empty());
        AtomicInteger calls = new AtomicInteger();
        try (var ontology = new OntologyService(new OntologyStore(root.resolve("ontology.db")), source::get)) {
            ontology.status(); source.set(null);
            var service = service(ontology, valid(), "secret", (c, p, s) -> calls.incrementAndGet());
            assertThrows(IllegalStateException.class, () -> service.sync(valid().fingerprint()));
            assertEquals(0, calls.get());
        }
    }
    @Test void cleanupPendingRetainsSingleFlightStatusAndTimeoutCode() {
        AtomicBoolean cleanup = new AtomicBoolean();
        AtomicInteger calls = new AtomicInteger();
        try (var ontology = ontology()) {
            var service = service(ontology, valid(), "synthetic", new Neo4jSyncService.Writer() {
                @Override public void write(Neo4jSyncConfig config, String password, Neo4jSnapshotWriter.Payload payload) {
                    calls.incrementAndGet(); cleanup.set(true); throw new IllegalStateException("neo4j.timeout");
                }
                @Override public boolean pendingCleanup() { return cleanup.get(); }
            });
            assertEquals("neo4j.timeout", assertThrows(IllegalStateException.class,
                    () -> service.sync(valid().fingerprint())).getMessage());
            assertEquals("running", service.status().get("state"));
            assertEquals("neo4j.busy", assertThrows(IllegalStateException.class,
                    () -> service.sync(valid().fingerprint())).getMessage());
            assertEquals(1, calls.get());
            cleanup.set(false);
            assertEquals("error", service.status().get("state"));
            assertDoesNotThrow(ontology::status);
        }
    }
}
