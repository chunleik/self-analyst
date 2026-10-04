package com.selfanalyst.config;

import com.selfanalyst.desktop.store.UserConfigStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import static org.junit.jupiter.api.Assertions.*;

class Neo4jConfigResolverTest {
    @TempDir Path root;

    @Test void defaultsAreOffAndOnlyEnvironmentNameIsConfiguration() {
        var snapshot = ConfigResolver.resolve(new Properties(), Map.of("SELF_ANALYST_NEO4J_PASSWORD", "synthetic-secret"));
        var config = Neo4jConfigResolver.from(snapshot.properties());
        assertFalse(config.enabled());
        assertEquals("", config.uri()); assertEquals("", config.namespace());
        assertEquals("neo4j", config.database()); assertEquals("neo4j", config.username());
        assertEquals("SELF_ANALYST_NEO4J_PASSWORD", config.passwordEnv()); assertEquals(15, config.timeoutSeconds());
        assertFalse(snapshot.properties().toString().contains("synthetic-secret"));
        assertFalse(snapshot.publicValues().toString().contains("synthetic-secret"));
        ConfigPolicy.NEO4J.forEach(key -> {
            assertTrue(SupportedKeys.contains(key)); assertFalse(ConfigPolicy.requiresRestart(key));
            assertEquals("neo4j", ConfigPolicy.component(key));
            assertNotNull(SupportedKeys.descriptions().get(key));
        });
    }

    @Test void invalidOptionalConfigDoesNotBlockCoreStartup() throws Exception {
        var store = new UserConfigStore(root);
        store.saveRaw("[neo4j]\nenabled=true\nuri='not-a-uri'\ntimeout-seconds='broken'\n");
        assertDoesNotThrow(() -> Config.load(root, Map.of()));
        var service = new ConfigApplicationService(store, Config.load(root, Map.of()), Map.of());
        var invalid = Neo4jConfigResolver.from(service.saved().properties());
        assertEquals(0, invalid.timeoutSeconds());
        assertThrows(IllegalArgumentException.class, invalid::validate);
    }

    @Test void savedConfigIsEffectiveForNextManualActionWithoutRestart() throws Exception {
        var store = new UserConfigStore(root);
        var service = new ConfigApplicationService(store, Config.load(root, Map.of()), Map.of());
        var before = Neo4jConfigResolver.from(service.saved().properties());
        var saved = service.saveRaw("[neo4j]\nenabled=true\nuri='bolt://127.0.0.1:7687'\nnamespace='synthetic-a'\n");
        assertTrue(saved.unknownKeys().isEmpty());
        assertTrue(saved.restartRequired().stream().noneMatch(ConfigPolicy.NEO4J::contains));
        String template = TomlSupport.buildTemplate(SupportedKeys.defaults(), SupportedKeys.types(), SupportedKeys.descriptions());
        assertTrue(template.contains("[neo4j]"));
        assertTrue(template.contains("password-env"));
        var after = Neo4jConfigResolver.from(service.saved().properties());
        assertTrue(after.enabled()); assertEquals("synthetic-a", after.namespace());
        assertNotEquals(before.fingerprint(), after.fingerprint());
        assertEquals("true", ((Map<?, ?>) service.effectivePayload().get("running")).get("neo4j.enabled"));
        assertEquals("next_manual_sync", ((Map<?, ?>) saved.application().get("neo4j")).get("status"));
        service.update(Map.of("neo4j.namespace", "synthetic-b"));
        assertEquals("synthetic-b", Neo4jConfigResolver.from(service.saved().properties()).namespace());
        service.update(Map.of("neo4j.enabled", "false"));
        assertFalse(Neo4jConfigResolver.from(service.saved().properties()).enabled());
    }

    @Test void savesRejectEmbeddedSecretsAndEffectiveMetadataRedactsBrokenUri() throws Exception {
        var store = new UserConfigStore(root);
        var service = new ConfigApplicationService(store, Config.load(root, Map.of()), Map.of());
        for (String unsafe : new String[]{"password-env='not a variable name'", "password='synthetic-secret'", "passwordValue='synthetic-secret'",
                "uri='bolt://user:synthetic-secret@localhost:7687'", "uri='bolt://localhost?password=synthetic-secret'"}) {
            var failure = assertThrows(TomlValidationException.class, () -> service.saveRaw("[neo4j]\n" + unsafe));
            assertFalse(failure.getMessage().contains("synthetic-secret"));
        }
        store.saveRaw("[neo4j]\nuri='bolt://user:synthetic-secret@localhost:7687'\n");
        assertFalse(service.effectivePayload().toString().contains("synthetic-secret"));
        assertEquals("[invalid URI]", Neo4jConfigResolver.publicValue("neo4j.uri", "broken:synthetic-secret"));
    }
}
