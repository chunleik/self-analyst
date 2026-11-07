package com.selfanalyst.content;

import com.selfanalyst.content.capture.TitleCaptureResult;
import com.selfanalyst.content.platform.PlatformCapture;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import com.selfanalyst.content.uia.UiaTreeWalker;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ContentWatcherTest {

    @Test void excludedApplicationNeverQueriesUiaOrSendsHeartbeatAndResumeIsFresh() {
        Platform platform = new Platform();
        Walker walker = new Walker();
        Probe watcher = new Probe(platform, walker, app -> "private.exe".equalsIgnoreCase(app));
        watcher.captureOnce(); watcher.heartbeatOnce();
        Object firstSession = watcher.sent.getFirst().get("captureSessionId");
        platform.app = "PRIVATE.EXE";
        watcher.captureOnce(); watcher.heartbeatOnce();
        assertEquals(1, walker.calls);
        assertEquals(1, watcher.sent.size());
        platform.app = "editor.exe";
        watcher.captureOnce(); watcher.heartbeatOnce();
        assertEquals(2, walker.calls);
        assertEquals(2, watcher.sent.size());
        assertNotEquals(firstSession, watcher.sent.getLast().get("captureSessionId"));
        watcher.shutdown();
    }

    @Test void dynamicPredicateChecksExistingSnapshotBeforeHeartbeatAndUsesFullName() {
        Platform platform = new Platform(); platform.app = "PotPlayerMini64.exe";
        Walker walker = new Walker();
        AtomicReference<String> excluded = new AtomicReference<>("PotPlayer.exe");
        Probe watcher = new Probe(platform, walker, app -> excluded.get().equalsIgnoreCase(app));
        watcher.captureOnce(); watcher.heartbeatOnce();
        assertEquals(1, watcher.sent.size());
        excluded.set("POTPLAYERMINI64.EXE");
        watcher.heartbeatOnce(); watcher.captureOnce();
        assertEquals(1, watcher.sent.size()); assertEquals(1, walker.calls);
        excluded.set("");
        watcher.heartbeatOnce(); assertEquals(1, watcher.sent.size());
        watcher.captureOnce(); watcher.heartbeatOnce();
        assertEquals(2, watcher.sent.size()); assertEquals(2, walker.calls);
        watcher.shutdown();
    }

    @Test void exclusionDuringUiaDiscardsResultBeforePublication() {
        Platform platform = new Platform(); Walker walker = new Walker();
        AtomicReference<String> excluded = new AtomicReference<>("");
        Probe watcher = new Probe(platform, walker, app -> excluded.get().equalsIgnoreCase(app));
        walker.onWalk = () -> excluded.set("editor.exe");
        watcher.captureOnce();
        excluded.set(""); watcher.heartbeatOnce();
        assertEquals(0, watcher.sent.size());
        walker.onWalk = () -> {};
        watcher.captureOnce(); watcher.heartbeatOnce();
        assertEquals(1, watcher.sent.size()); assertEquals(2, walker.calls);
        watcher.shutdown();
    }

    @Test void exclusionObservedByHeartbeatInvalidatesAnInFlightCaptureEvenAfterResume() {
        Platform platform = new Platform(); Walker walker = new Walker();
        AtomicReference<String> excluded = new AtomicReference<>("");
        Probe watcher = new Probe(platform, walker, app -> excluded.get().equalsIgnoreCase(app));
        walker.onWalk = () -> {
            excluded.set("editor.exe"); watcher.heartbeatOnce(); excluded.set("");
        };
        watcher.captureOnce(); watcher.heartbeatOnce();
        assertEquals(0, watcher.sent.size());
        walker.onWalk = () -> {};
        watcher.captureOnce(); watcher.heartbeatOnce();
        assertEquals(1, watcher.sent.size());
        watcher.shutdown();
    }

    @Test void newlyExcludedRetryCannotLeakAfterSwitchingToAllowedForeground() {
        Platform platform = new Platform(); Walker walker = new Walker();
        AtomicReference<String> excluded = new AtomicReference<>("");
        Probe watcher = new Probe(platform, walker, app -> excluded.get().equalsIgnoreCase(app));
        watcher.success = false; watcher.captureOnce(); watcher.heartbeatOnce();
        Object firstId = watcher.sent.getFirst().get("sourceEventId");
        excluded.set("editor.exe"); platform.app = "other.exe";
        watcher.success = true; watcher.captureOnce(); watcher.heartbeatOnce();
        assertEquals(2, watcher.sent.size());
        assertEquals("other.exe", ((Map<?, ?>) watcher.sent.getLast().get("data")).get("app"));
        assertNotEquals(firstId, watcher.sent.getLast().get("sourceEventId"));
        watcher.shutdown();
    }

    @Test void excludedForegroundClearsPendingAllowedRetryAndResetsSession() {
        Platform platform = new Platform(); Walker walker = new Walker();
        Probe watcher = new Probe(platform, walker, app -> "private.exe".equalsIgnoreCase(app));
        watcher.success = false; watcher.captureOnce(); watcher.heartbeatOnce();
        Object firstId = watcher.sent.getFirst().get("sourceEventId");
        Object firstSession = watcher.sent.getFirst().get("captureSessionId");
        platform.app = "private.exe"; watcher.heartbeatOnce();
        assertEquals(1, watcher.sent.size());
        platform.app = "editor.exe"; watcher.success = true;
        watcher.captureOnce(); watcher.heartbeatOnce();
        assertEquals(2, watcher.sent.size()); assertEquals(2, walker.calls);
        assertNotEquals(firstId, watcher.sent.getLast().get("sourceEventId"));
        assertNotEquals(firstSession, watcher.sent.getLast().get("captureSessionId"));
        watcher.shutdown();
    }

    private static final class Platform implements PlatformCapture {
        String app = "editor.exe";
        public ForegroundWindow getForegroundWindowInfo() { return new ForegroundWindow(1, app, "Title"); }
    }
    private static final class Walker extends UiaTreeWalker {
        int calls;
        Runnable onWalk = () -> {};
        public UiaWalkResult walk(long handle) {
            calls++; onWalk.run(); return new UiaWalkResult("", null);
        }
    }
    private static final class Probe extends ContentWatcher {
        final List<Map<String, Object>> sent = new ArrayList<>();
        boolean success = true;
        Probe(Platform platform, Walker walker, Predicate<String> excluded) {
            super("http://127.0.0.1:1", 2000, excluded, platform, walker);
        }
        protected boolean deliverHeartbeat(String url, Map<String, Object> body) {
            sent.add(body); return success;
        }
    }

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
