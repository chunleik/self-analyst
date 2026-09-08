package com.selfanalyst.config;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ConfigResolverTest {
    @Test void sourcesFollowPriorityAndDoNotRevealKeys() {
        Properties user = new Properties();
        user.setProperty("llm.api-key", "secret-toml");
        var snapshot = ConfigResolver.resolve(user, Map.of("OPENAI_API_KEY", "secret-env", "LLM_MODEL", "env-model"));
        assertEquals("secret-toml", snapshot.config().llmApiKey());
        assertEquals("toml", snapshot.values().get("llm.api-key").source());
        assertEquals("environment", snapshot.values().get("llm.model").source());
        assertEquals("inherited", snapshot.values().get("embedding.api-key").source());
        assertEquals("secret-toml", snapshot.values().get("embedding.api-key").value());
        assertFalse(snapshot.publicValues().toString().contains("secret-"));
    }
    @Test void blankAndRemovalAreDistinct() {
        Properties user = new Properties();
        user.setProperty("llm.api-key", "");
        assertEquals("", ConfigResolver.resolve(user, Map.of("OPENAI_API_KEY", "env")).config().llmApiKey());
        user.remove("llm.api-key");
        assertEquals("env", ConfigResolver.resolve(user, Map.of("OPENAI_API_KEY", "env")).config().llmApiKey());
    }
    @Test void runtimeSnapshotsContainNormalizedValues() {
        Properties user = new Properties();
        user.setProperty("embedding.dimensions", "2048");
        user.setProperty("llm.budget.mode", "invalid");
        var snapshot = ConfigResolver.resolve(user, Map.of());
        assertEquals("1024", snapshot.values().get("embedding.dimensions").value());
        assertEquals("warn", snapshot.values().get("llm.budget.mode").value());
    }
    @Test void policiesDistinguishHotAndStartupParameters() {
        ConfigPolicy.LLM.forEach(key -> assertFalse(ConfigPolicy.requiresRestart(key), key));
        for (String key : List.of("llm.agent.maxIters", "agent.compaction.triggerTokens", "llm.budget.mode", "aw.port"))
            assertTrue(ConfigPolicy.requiresRestart(key), key);
        assertFalse(ConfigPolicy.requiresRestart("unknown.key"));
    }
}
