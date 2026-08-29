package com.selfanalyst.content;

import com.selfanalyst.content.platform.PlatformCapture;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContentWatcherTest {

    @Test
    void stableWindowUsesConfiguredCaptureInterval() {
        long interval = TimeUnit.MILLISECONDS.toNanos(1_500);
        assertTrue(ContentWatcher.captureIsDue(false, 0, Long.MIN_VALUE, interval));
        assertFalse(ContentWatcher.captureIsDue(false, interval - 1, 0, interval));
        assertTrue(ContentWatcher.captureIsDue(false, interval, 0, interval));
    }

    @Test
    void windowChangeTriggersCaptureImmediately() {
        assertTrue(ContentWatcher.captureIsDue(true, 100, 0, 1_500));
    }

    @Test
    void foregroundSnapshotMustStillMatchBeforePublishing() {
        PlatformCapture.ForegroundWindow expected =
                new PlatformCapture.ForegroundWindow(10L, "app.exe", "Title");

        assertTrue(ContentWatcher.sameWindow(10L, "app.exe", "Title", expected));
        assertFalse(ContentWatcher.sameWindow(11L, "app.exe", "Title", expected));
        assertFalse(ContentWatcher.sameWindow(10L, "other.exe", "Title", expected));
        assertFalse(ContentWatcher.sameWindow(10L, "app.exe", "Other", expected));
    }
}
