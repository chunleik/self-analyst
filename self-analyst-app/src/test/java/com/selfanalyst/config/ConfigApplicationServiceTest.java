package com.selfanalyst.config;

import com.selfanalyst.desktop.store.UserConfigStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class ConfigApplicationServiceTest {
    @TempDir Path dir;
    private ConfigApplicationService service(UserConfigStore store) {
        Config initial = ConfigResolver.resolve(store.loadUser(), Map.of()).config();
        return new ConfigApplicationService(store, initial, Map.of());
    }
    private LlmRuntimeManager<AutoCloseable> attach(ConfigApplicationService service, AtomicInteger closes) {
        var runtime = new LlmRuntimeManager<AutoCloseable>(service.commitLock(),
                LlmSettings.from(service.saved().config()), settings -> {
                    if (settings.model().equals("reject")) throw new IllegalStateException("secret-should-not-leak");
                    return closes::incrementAndGet;
                });
        service.attach(runtime);
        return runtime;
    }
    @Test void mixedSavePublishesOnlyLlmAndPendingRestartPersists() throws Exception {
        var store = new UserConfigStore(dir);
        var service = service(store);
        try (var runtime = attach(service, new AtomicInteger())) {
            var result = service.update(Map.of("llm.model", "new", "llm.api-key", "secret",
                    "aw.port", "5799", "agent.compaction.triggerTokens", "8000"));
            assertEquals("new", runtime.settings().model());
            assertTrue(result.restartRequired().contains("aw.port"));
            assertFalse(result.restartRequired().contains("llm.model"));
            long generation = result.llmRevision();
            assertEquals(generation, service.saveRaw(store.readRaw() + "\n# comment").llmRevision());
            assertTrue(service.update(Map.of("llm.temperature", "0.4")).restartRequired().contains("aw.port"));
            String port = ConfigResolver.resolve(new Properties(), Map.of()).config().awPort() + "";
            assertFalse(service.update(Map.of("aw.port", port)).restartRequired().contains("aw.port"));
            assertFalse(service.effectivePayload().toString().contains("secret"));
        }
    }
    @Test void embeddingInheritanceReportsPendingDifference() throws Exception {
        var store = new UserConfigStore(dir);
        store.saveRaw("[llm]\napi-key='old'\n[embedding]\nenabled=true\n");
        var service = service(store);
        try (var runtime = attach(service, new AtomicInteger())) {
            var result = service.update(Map.of("llm.api-key", "new"));
            assertTrue(result.restartRequired().contains("embedding.api-key"));
            assertEquals("new", runtime.settings().apiKey());
        }
    }
    @Test void explicitDefaultWinsOverEnvironmentAndDeletionRestoresIt() throws Exception {
        var store = new UserConfigStore(dir);
        Map<String, String> env = Map.of("LLM_MODEL", "env-model");
        var service = new ConfigApplicationService(store, ConfigResolver.resolve(new Properties(), env).config(), env);
        service.update(Map.of("llm.model", "gpt-4o"));
        assertEquals("gpt-4o", store.loadUser().getProperty("llm.model"));
        assertEquals("gpt-4o", service.saved().config().llmModel());
        service.update(Map.of("llm.model", ""));
        assertEquals("env-model", service.saved().config().llmModel());
    }
    @Test void prepareAndWriteFailureRetainBothOldStatesAndDisposeCandidate() throws Exception {
        AtomicBoolean failWrite = new AtomicBoolean();
        var store = new UserConfigStore(dir) {
            @Override public void saveRaw(String text) throws IOException {
                if (failWrite.get()) throw new IOException("secret-io");
                super.saveRaw(text);
            }
        };
        store.saveRaw("[llm]\nmodel='old'\n");
        var service = service(store);
        AtomicInteger closes = new AtomicInteger();
        try (var runtime = attach(service, closes)) {
            String before = store.readRaw();
            var error = assertThrows(IllegalStateException.class, () -> service.update(Map.of("llm.model", "reject")));
            assertFalse(error.toString().contains("secret"));
            assertEquals(before, store.readRaw());
            failWrite.set(true);
            assertThrows(IOException.class, () -> service.update(Map.of("llm.model", "new")));
            assertEquals(before, store.readRaw());
            assertEquals("old", runtime.settings().model());
            assertEquals(1, closes.get());
        }
    }
    @Test void concurrentPatchesKeepBothKeysAndCloseRejectsSaves() throws Exception {
        var store = new UserConfigStore(dir);
        var service = service(store);
        try (var runtime = attach(service, new AtomicInteger());
             var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> service.update(Map.of("llm.model", "new")));
            var b = pool.submit(() -> service.update(Map.of("llm.temperature", "0.3")));
            var first = a.get(5, TimeUnit.SECONDS);
            var second = b.get(5, TimeUnit.SECONDS);
            assertNotEquals(first.revision(), second.revision());
            assertEquals("new", service.saved().config().llmModel());
            assertEquals(.3, runtime.settings().temperature());
            service.close();
            assertThrows(IllegalStateException.class, () -> service.update(Map.of("llm.model", "closed")));
        }
    }
    @Test void shutdownDuringPreparationAllowsAcceptedCommitButRejectsNewSaves() throws Exception {
        var store = new UserConfigStore(dir);
        var service = service(store);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var runtime = new LlmRuntimeManager<AutoCloseable>(service.commitLock(),
                LlmSettings.from(service.saved().config()), settings -> {
                    if (settings.model().equals("slow")) {
                        entered.countDown();
                        try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); }
                        catch (InterruptedException error) { throw new IllegalStateException(error); }
                    }
                    return () -> {};
                });
        service.attach(runtime);
        try (var pool = Executors.newFixedThreadPool(3)) {
            var accepted = pool.submit(() -> service.update(Map.of("llm.model", "slow")));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            service.close();
            var shutdown = pool.submit(runtime::close);
            var rejected = pool.submit(() -> assertThrows(IllegalStateException.class,
                    () -> service.update(Map.of("llm.model", "too-late"))));
            release.countDown();
            assertEquals(1, accepted.get(5, TimeUnit.SECONDS).revision());
            shutdown.get(5, TimeUnit.SECONDS);
            rejected.get(5, TimeUnit.SECONDS);
            assertEquals("slow", service.saved().config().llmModel());
            assertThrows(IllegalStateException.class, runtime::acquire);
        } finally {
            release.countDown();
            runtime.close();
        }
    }

    @Test void invalidSettingsCannotReplaceSavedFile() throws Exception {
        var store = new UserConfigStore(dir);
        var service = service(store);
        for (var update : List.of(Map.of("llm.temperature", "NaN"), Map.of("llm.temperature", "2.1"),
                Map.of("llm.max-tokens", "-1"), Map.of("llm.base-url", "file:///secret"))) {
            assertThrows(TomlValidationException.class, () -> service.update(update));
            assertFalse(Files.exists(store.filePath()));
        }
    }
}
