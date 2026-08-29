package com.selfanalyst.content;

import com.selfanalyst.content.capture.ContentResult;
import com.selfanalyst.content.platform.PlatformCapture;
import com.selfanalyst.content.uia.UiaTreeWalker;
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
    void weixinConversationChangeRefreshesUiaWithStableWindowIdentity() {
        UiaTreeWalker.UiaWalkResult oldConversation =
                new UiaTreeWalker.UiaWalkResult("旧会话\n聊天记录\n聊天信息", null);

        assertFalse(ContentWatcher.canReuseCachedUia(
                "Weixin.exe", 10L, "微信", 10L, "微信", oldConversation));
        assertFalse(ContentWatcher.canReuseCachedUia(
                "WeChat.exe", 10L, "微信", 10L, "微信", oldConversation));
    }

    @Test
    void stableOrdinaryWindowCanReuseCachedUia() {
        UiaTreeWalker.UiaWalkResult cached =
                new UiaTreeWalker.UiaWalkResult("Document", null);

        assertTrue(ContentWatcher.canReuseCachedUia(
                "editor.exe", 10L, "Document", 10L, "Document", cached));
    }

    @Test
    void heartbeatIncludesStructuredContextTitleWhenExtracted() {
        ContentResult result = new ContentResult(
                "text", "uia", 544, 0, "徐工3期小分队(3)", "sample-123");

        Map<String, Object> data = ContentWatcher.heartbeatData(
                "Weixin.exe", "微信", result);

        assertEquals("微信", data.get("title"));
        assertEquals("徐工3期小分队(3)", data.get("context_title"));
        assertEquals("sample-123", data.get("sample_id"));
    }

    @Test
    void heartbeatOmitsContextTitleWhenItWasNotExtracted() {
        ContentResult result = new ContentResult(
                "text", "uia", 200, 0, null, null);

        Map<String, Object> data = ContentWatcher.heartbeatData(
                "editor.exe", "Document", result);

        assertFalse(data.containsKey("context_title"));
    }
}
