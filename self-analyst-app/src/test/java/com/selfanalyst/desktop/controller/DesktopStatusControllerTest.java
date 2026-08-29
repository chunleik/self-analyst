package com.selfanalyst.desktop.controller;

import com.selfanalyst.config.Config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

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
}
