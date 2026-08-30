package com.selfanalyst.content;

import com.selfanalyst.content.capture.TitleCapture;
import com.selfanalyst.content.capture.TitleCaptureResult;
import com.selfanalyst.content.platform.WindowsCapture;
import com.selfanalyst.content.uia.UiaTreeWalker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Explicit local smoke test; never queries a real foreground window during normal mvn test. */
@EnabledIfSystemProperty(named = "selfanalyst.manual.uia", matches = "true")
class ManualUiaSmokeTest {

    @Test
    void capturesAValidTitleProjectionFromTheForegroundWindow() {
        assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"));

        WindowsCapture windows = new WindowsCapture();
        var foreground = windows.getForegroundWindowInfo();
        assumeTrue(foreground.handle() != 0, "No foreground window is available");

        UiaTreeWalker.UiaWalkResult walk = new UiaTreeWalker().walk(foreground.handle());
        TitleCaptureResult result = new TitleCapture().capture(
                foreground.app(), walk.root(), walk.text());

        assertNotNull(result);
        assertTrue(result.uiaChars() >= 0);
        assertTrue(Set.of("window", "uia_context", "uia_document")
                .contains(result.titleSource()));
        System.out.printf(
                "UIA smoke test: app=%s, windowTitlePresent=%s, uiaChars=%d, contextTitleDetected=%s%n",
                foreground.app(),
                foreground.title() != null && !foreground.title().isBlank(),
                result.uiaChars(),
                result.contextTitle() != null);
    }
}
