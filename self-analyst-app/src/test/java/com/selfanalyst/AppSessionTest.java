package com.selfanalyst;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppSessionTest {

    @Test
    void desktopUiStartupLogMessageIncludesDesktopUiUrl() {
        assertEquals(
                "Desktop UI 已就绪: http://localhost:5701/desktop-ui/",
                AppSession.desktopUiStartupLogMessage(5701));
    }

    @Test
    void invalidLegacyWatchPathDoesNotAbortStartupParsing() {
        assertTrue(AppSession.parseWatchRoots("bad\u0000path").isEmpty());
    }
}
