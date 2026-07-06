package com.selfanalyst.agent;

import com.selfanalyst.config.Config;
import com.selfanalyst.headroom.HeadroomService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SelfAnalystAgentHeadroomTest {

    @Test
    void effectiveBaseUrlUsesConfigWhenHeadroomMissing(@TempDir Path dir) {
        Config config = Config.testDefaults(dir);

        assertEquals(config.llmBaseUrl(), SelfAnalystAgent.effectiveLlmBaseUrl(config, null));
    }

    @Test
    void effectiveBaseUrlUsesHeadroomSnapshotWhenPresent(@TempDir Path dir) {
        Config config = Config.testDefaults(dir);
        HeadroomService service = new HeadroomService(
                true,
                "http://127.0.0.1:8787/v1",
                config.llmBaseUrl(),
                true,
                false,
                (uri, timeout) -> HeadroomService.ProbeResult.ok("reachable"));

        assertEquals("http://127.0.0.1:8787/v1",
                SelfAnalystAgent.effectiveLlmBaseUrl(config, service));
    }
}
