package com.selfanalyst;

import com.selfanalyst.config.Config;
import com.selfanalyst.desktop.store.UserConfigStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class AppSessionLlmRecoveryTest {
    @Test void firstSetupRecoversWithoutReplacingApplicationServices(@TempDir Path dir) throws Exception {
        var store = new UserConfigStore(dir.resolve("config"));
        store.saveRaw("""
                memory.dir = '%s'
                [aw]
                mode = 'external'
                [aw.collection]
                window = false
                afk = false
                content = false
                [llm]
                api-key = ''
                [wiki]
                enabled = false
                [embedding]
                enabled = false
                [websearch]
                enabled = false
                [file.watch]
                enabled = false
                """.formatted(dir));
        Config config = Config.load(store.filePath().getParent());
        try (AppSession session = new AppSession(null, () -> {}, config, store)) {
            var agent = session.agent();
            assertNotNull(agent);
            assertNotNull(agent.memory());
            assertFalse(agent.isLlmAvailable());
            agent.configuration().update(Map.of("llm.api-key", "local-test-key", "llm.model", "configured"));
            assertSame(agent, session.agent());
            assertTrue(agent.isLlmAvailable());
            assertEquals("configured", agent.llmSettings().model());
            assertFalse(session.config().wikiEnabled());
            assertFalse(session.config().embeddingEnabled());
            session.saveAndShutdown();
            assertThrows(IllegalStateException.class, () -> agent.configuration().update(Map.of("llm.model", "closed")));
        }
    }
}
