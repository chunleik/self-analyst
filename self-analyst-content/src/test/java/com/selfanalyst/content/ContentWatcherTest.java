package com.selfanalyst.content;

import com.selfanalyst.content.capture.TitleCaptureResult;
import com.selfanalyst.content.platform.PlatformCapture;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void heartbeatIncludesStructuredContextTitleWhenExtracted() {
        TitleCaptureResult titleResult = new TitleCaptureResult(
                "徐工3期小分队(3)", "chat", "uia_context", "high", 544);

        Map<String, Object> data = ContentWatcher.heartbeatData(
                "Weixin.exe", "微信", titleResult);

        assertEquals(2, data.get("schema_version"));
        assertEquals("微信", data.get("title"));
        assertEquals("徐工3期小分队(3)", data.get("context_title"));
        assertFalse(data.containsKey("text_content"));
        assertFalse(data.containsKey("sample_id"));
        assertFalse(data.toString().contains("SELF_ANALYST_FORBIDDEN_BODY_7F3A"));
    }

    @Test
    void heartbeatOmitsContextTitleWhenItWasNotExtracted() {
        TitleCaptureResult titleResult = new TitleCaptureResult(
                null, null, "window", null, 200);

        Map<String, Object> data = ContentWatcher.heartbeatData(
                "editor.exe", "Document", titleResult);

        assertFalse(data.containsKey("context_title"));
        assertEquals("window", data.get("title_source"));
    }

    @Test
    void heartbeatFailureMakesRuntimeStatusDegraded() {
        assertEquals("running", ContentWatcher.statusOf(true, true));
        assertEquals("degraded", ContentWatcher.statusOf(true, false));
        assertEquals("disabled", ContentWatcher.statusOf(false, false));
    }

    @Test
    void heartbeatTargetsProductContentBucket() {
        assertEquals("watcher-content", ContentWatcher.CLIENT);
        String bucketId = ContentWatcher.bucketIdForHostname("testhost");
        assertEquals("watcher-content_testhost", bucketId);
        assertFalse(bucketId.startsWith("aw-watcher-"));
    }
}
