package com.selfanalyst.desktop.controller;

import com.selfanalyst.config.Config;
import com.selfanalyst.headroom.HeadroomService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

        try {
            Map<String, Object> llm = ctrl.buildLlmStatus(false);
            Map<String, Object> headroom = ctrl.buildHeadroomStatus();

            assertEquals("http://127.0.0.1:8787/v1", llm.get("baseUrl"));
            assertEquals("available", headroom.get("status"));
            assertEquals(true, headroom.get("enabled"));
        } finally {
            ctrl.close();
        }
    }

    @Test
    void availabilityCheckUsesStartupHeadroomSnapshotWithoutRefreshing(@TempDir Path dir) {
        Config config = Config.testDefaults(dir);
        AtomicInteger probes = new AtomicInteger();
        HeadroomService service = new HeadroomService(
                true,
                "http://127.0.0.1:8787/v1",
                config.llmBaseUrl(),
                true,
                false,
                (uri, timeout) -> probes.incrementAndGet() == 1
                        ? HeadroomService.ProbeResult.fail("connection refused")
                        : HeadroomService.ProbeResult.ok("reachable"));
        DesktopStatusController ctrl = new DesktopStatusController(config, null, null, null, service);

        try {
            assertEquals("fallback", service.snapshot().status());

            assertEquals(config.llmBaseUrl(), ctrl.effectiveBaseUrlForAvailabilityCheck());
            assertEquals("fallback", ctrl.buildHeadroomStatus().get("status"));
            assertEquals(1, probes.get());

            service.refresh();
            assertEquals("available", ctrl.buildHeadroomStatus().get("status"));
        } finally {
            ctrl.close();
        }
    }

    @Test
    void closeShutsDownLlmCheckerAndIsIdempotent(@TempDir Path dir) {
        Config config = Config.testDefaults(dir);
        DesktopStatusController ctrl = new DesktopStatusController(config, null, null, null);

        ctrl.close();
        ctrl.close();

        assertTrue(ctrl.isLlmCheckerShutdownForTest());
    }
}
