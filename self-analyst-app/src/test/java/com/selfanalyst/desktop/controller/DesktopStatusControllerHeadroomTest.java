package com.selfanalyst.desktop.controller;

import com.selfanalyst.config.Config;
import com.selfanalyst.headroom.HeadroomService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DesktopStatusControllerHeadroomTest {

    @Test
    void llmStatusUsesEffectiveHeadroomBaseUrl(@TempDir Path dir) {
        Config config = Config.testDefaults(dir);
        HeadroomService service = new HeadroomService(
                true,
                "http://127.0.0.1:8787/v1",
                config.llmBaseUrl(),
                true,
                false,
                (uri, timeout) -> HeadroomService.ProbeResult.ok("reachable"));
        DesktopStatusController ctrl = new DesktopStatusController(config, null, null, null, service);

        Map<String, Object> llm = ctrl.buildLlmStatus(false);
        Map<String, Object> headroom = ctrl.buildHeadroomStatus();

        assertEquals("http://127.0.0.1:8787/v1", llm.get("baseUrl"));
        assertEquals("available", headroom.get("status"));
        assertEquals(true, headroom.get("enabled"));
    }
}
