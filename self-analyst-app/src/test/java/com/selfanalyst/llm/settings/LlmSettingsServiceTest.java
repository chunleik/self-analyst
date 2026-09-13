package com.selfanalyst.llm.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfanalyst.config.*;
import com.selfanalyst.desktop.store.UserConfigStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LlmSettingsServiceTest {
    @TempDir Path dir;
    private LlmSettingsService service(UserConfigStore store) {
        Map<String, String> env = Map.of("OPENAI_API_KEY", "private-environment-key", "LLM_MODEL", "environment-model");
        var configuration = new ConfigApplicationService(store, ConfigResolver.resolve(store.loadUser(), env).config(), env);
        return new LlmSettingsService(new TomlLlmSettingsRepository(configuration));
    }
    @Test void readsWithoutWritingAndKeepsCredentialsPrivate() throws Exception {
        var store = new UserConfigStore(dir); var service = service(store);
        var snapshot = service.read();
        assertEquals("environment-model", snapshot.fields().get("model").effectiveValue());
        assertEquals("environment", snapshot.credential().source());
        assertTrue(snapshot.credential().configured());
        assertFalse(Files.exists(store.filePath()));
        String json = new ObjectMapper().writeValueAsString(snapshot);
        assertFalse(json.contains("private-environment-key")); assertFalse(json.contains("****"));
    }
    @Test void partialUpdatesClearAndResetPreserveOverrides() throws Exception {
        var store = new UserConfigStore(dir); var service = service(store);
        store.saveRaw("# 注释\n[other]\nunknown='keep'\n");
        service.save(Map.of("updates", Map.of("model", "custom")));
        assertTrue(service.read().credential().configured());
        assertTrue(store.readRaw().endsWith("# 注释\n[other]\nunknown='keep'\n"));
        service.save(Map.of("credential", Map.of("action", "replace", "value", "local-key")));
        service.save(Map.of("credential", Map.of("action", "clear")));
        assertEquals("", store.loadUser().getProperty("llm.api-key"));
        assertFalse(service.read().credential().configured());
        service.save(Map.of("credential", Map.of("action", "reset"), "reset", List.of("model")));
        assertTrue(service.read().credential().configured());
        assertEquals("environment-model", service.read().fields().get("model").effectiveValue());
        assertFalse(store.loadUser().containsKey("llm.api-key"));
    }
    @Test void invalidFieldsCannotPersistAndProbeDoesNotSave() throws Exception {
        var store = new UserConfigStore(dir); var service = service(store);
        for (var request : List.of(Map.of("protocol", "anthropic"), Map.of("updates", Map.of("maxTokens", 0.5)),
                Map.of("updates", Map.of("temperature", 3)), Map.of("updates", Map.of("model", "")),
                Map.of("updates", Map.of("model", "x"), "reset", List.of("model")),
                Map.of("credential", Map.of("action", "keep", "value", "oops")),
                Map.of("credential", Map.of("action", "replace", "value", "")))) {
            assertThrows(IllegalArgumentException.class, () -> service.save(new LinkedHashMap<>(request)));
        }
        assertThrows(IllegalArgumentException.class, () -> service.connection(Map.of("baseUrl", "http://127.0.0.1:1234/v1"), false));
        var connection = service.connection(Map.of("baseUrl", "http://127.0.0.1:1234/v1", "model", "test",
                "credential", Map.of("action", "replace", "value", "probe-key")), false);
        assertEquals("probe-key", connection.apiKey());
        assertFalse(Files.exists(store.filePath()));
        assertThrows(IllegalArgumentException.class, () -> LlmSettingsService.baseUrl("https://example.org/v1?key=secret"));
    }
    @Test void invalidFileIsNotTreatedAsEmpty() throws Exception {
        var store = new UserConfigStore(dir); var service = service(store);
        store.saveRaw("[invalid");
        assertThrows(RuntimeException.class, service::read);
        assertThrows(RuntimeException.class, () -> service.save(Map.of("updates", Map.of("model", "x"))));
        assertEquals("[invalid", store.readRaw());
    }
}
