package com.selfanalyst.ontology;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Neo4jSyncConfigTest {
    private Neo4jSyncConfig config(String uri) { return new Neo4jSyncConfig(true, uri, "neo4j", "neo4j", "NEO4J_PASSWORD", "test", 15); }
    @Test void permitsOnlyLoopbackBoltOrCaValidatedTls() {
        for (String uri : new String[]{"bolt://localhost", "bolt://127.0.0.1:7687", "bolt://[::1]:7687", "neo4j+s://example.com", "bolt+s://example.com:7687"}) {
            assertDoesNotThrow(() -> config(uri).validate(), uri);
        }
        for (String uri : new String[]{"http://localhost", "bolt://example.com", "neo4j://localhost", "neo4j+ssc://example.com", "bolt+ssc://localhost",
                "bolt://localhost:0", "bolt://localhost:99999", "bolt://user:secret@localhost", "bolt://localhost/path", "bolt://localhost/",
                "bolt://localhost?q=secret", "bolt://localhost#secret", "not a URI", ""}) {
            var error = assertThrows(IllegalArgumentException.class, () -> config(uri).validate(), uri);
            assertEquals("neo4j.invalidConfig", error.getMessage());
            assertFalse(config(uri).publicTarget().toString().contains("secret"));
            assertEquals("", config(uri).fingerprint());
        }
    }
    @Test void validatesNamespaceDatabaseEnvironmentAndTimeout() {
        assertThrows(IllegalArgumentException.class, () -> new Neo4jSyncConfig(true, "bolt://localhost", "system", "user", "ENV", "test", 15).validate());
        assertThrows(IllegalArgumentException.class, () -> new Neo4jSyncConfig(true, "bolt://localhost", "neo4j", "user", "my-password-value", "test", 15).validate());
        assertThrows(IllegalArgumentException.class, () -> new Neo4jSyncConfig(true, "bolt://localhost", "neo4j", "user", "ENV", "", 15).validate());
        assertThrows(IllegalArgumentException.class, () -> new Neo4jSyncConfig(true, "bolt://localhost", "neo4j", "user", "ENV", "test", 121).validate());
        assertThrows(IllegalArgumentException.class, () -> new Neo4jSyncConfig(true, "bolt://localhost", "neo4j", "user", "ENV", "test", 0).validate());
    }
}
