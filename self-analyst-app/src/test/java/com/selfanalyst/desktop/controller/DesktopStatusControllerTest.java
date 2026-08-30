package com.selfanalyst.desktop.controller;

import com.selfanalyst.config.Config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopStatusControllerTest {

    @Test
    void llmStatusUsesConfiguredBaseUrlDirectly(@TempDir Path dir) {
        Config config = Config.testDefaults(dir);
        DesktopStatusController controller = new DesktopStatusController(
                config, null, null, null);

        try {
            assertEquals(config.llmBaseUrl(),
                    controller.buildLlmStatus(false).get("baseUrl"));
            assertEquals(config.llmBaseUrl(),
                    controller.effectiveBaseUrlForAvailabilityCheck());
            assertFalse(controller.statusPayload().containsKey("headroom"));
        } finally {
            controller.close();
        }
    }

    @Test
    void closeShutsDownLlmCheckerAndIsIdempotent(@TempDir Path dir) {
        DesktopStatusController controller = new DesktopStatusController(
                Config.testDefaults(dir), null, null, null);

        controller.close();
        controller.close();

        assertTrue(controller.isLlmCheckerShutdownForTest());
    }

    @Test
    @SuppressWarnings("unchecked")
    void reportsContextTitleMigrationFailureAsDegraded(@TempDir Path dir) {
        DesktopStatusController controller = new DesktopStatusController(
                Config.testDefaults(dir), null, null, null,
                false, "simulated migration failure");

        try {
            Map<String, Object> payload = controller.statusPayload();
            Map<String, String> collectors =
                    (Map<String, String>) payload.get("collectors");
            Map<String, Object> persistence =
                    (Map<String, Object>) payload.get("contentPersistence");

            assertEquals("degraded", collectors.get("contextTitle"));
            assertEquals("degraded", collectors.get("content"));
            assertEquals(false, persistence.get("ready"));
            assertEquals("migration_failed", persistence.get("status"));
        } finally {
            controller.close();
        }
    }
}
