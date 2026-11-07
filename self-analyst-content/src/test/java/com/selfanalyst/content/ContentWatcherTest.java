package com.selfanalyst.content;

import com.selfanalyst.content.capture.TitleCaptureResult;
import com.selfanalyst.content.platform.PlatformCapture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.IntConsumer;
import com.selfanalyst.content.uia.UiaTreeWalker;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ContentWatcherTest {

    @ParameterizedTest
    @CsvSource({
            "capture, false, false", "capture, true, false",
            "heartbeat, false, false", "heartbeat, true, false",
            "send, false, false", "send, true, false",
            "capture, false, true", "capture, true, true",
            "heartbeat, false, true", "heartbeat, true, true",
            "send, false, true", "send, true, true"
    })
    void unavailableForegroundPreservesSessionAndPendingRetry(
            String checkpoint, boolean nullForeground, boolean failedDelivery) {
        Platform platform = new Platform();
        Walker walker = new Walker();
        Probe watcher = new Probe(platform, walker, app -> false);
        watcher.success = !failedDelivery;
        watcher.captureOnce();
        watcher.heartbeatOnce();
        Map<String, Object> first = watcher.sent.getFirst();

        if (checkpoint.equals("send")) {
            int finalRead = platform.reads + 2;
            platform.onRead = read -> {
                if (read == finalRead) platform.makeUnavailable(nullForeground);
            };
            watcher.heartbeatOnce();
            platform.onRead = read -> {};
        } else {
            platform.makeUnavailable(nullForeground);
            if (checkpoint.equals("capture")) watcher.captureOnce();
            else watcher.heartbeatOnce();
        }
        assertEquals(1, watcher.sent.size(), "No heartbeat while the foreground is unavailable");
        assertEquals(1, walker.calls, "No UIA query while the foreground is unavailable");

        platform.restore();
        watcher.heartbeatOnce();
        assertEquals(1, watcher.sent.size(), "The old snapshot must not resume sending");
        watcher.captureOnce();
        watcher.heartbeatOnce();
        assertEquals(2, walker.calls, "Recovery must refresh the snapshot");
        assertEquals(2, watcher.sent.size());
        assertEquals(first.get("captureSessionId"), watcher.sent.getLast().get("captureSessionId"));
        if (failedDelivery) {
            assertEquals(first, watcher.sent.getLast(), "Retry must preserve the entire original request");
            watcher.heartbeatOnce();
            assertEquals(first, watcher.sent.getLast(), "The third real attempt must still retry");
            watcher.success = true;
            watcher.heartbeatOnce();
            assertNotEquals(first.get("sourceEventId"), watcher.sent.getLast().get("sourceEventId"));
            assertNotEquals(first.get("captureSessionId"), watcher.sent.getLast().get("captureSessionId"));
        } else {
            assertNotEquals(first.get("sourceEventId"), watcher.sent.getLast().get("sourceEventId"));
        }
        watcher.shutdown();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unavailableForegroundDuringUiaInvalidatesInFlightResultWithoutBreakingSession(
            boolean nullForeground) {
        Platform platform = new Platform();
        Walker walker = new Walker();
        Probe watcher = new Probe(platform, walker, app -> false);
        watcher.captureOnce();
        watcher.heartbeatOnce();
        Object session = watcher.sent.getFirst().get("captureSessionId");
        platform.title = "Updated title";
        walker.onWalk = () -> {
            platform.makeUnavailable(nullForeground);
            watcher.heartbeatOnce();
            platform.restore();
        };
        watcher.captureOnce();
        watcher.heartbeatOnce();
        assertEquals(1, watcher.sent.size(), "The in-flight result must stay invalid after recovery");
        walker.onWalk = () -> {};
        watcher.captureOnce();
        watcher.heartbeatOnce();
        assertEquals(3, walker.calls);
        assertEquals(2, watcher.sent.size());
        assertEquals(session, watcher.sent.getLast().get("captureSessionId"));
        assertEquals("Updated title", ((Map<?, ?>) watcher.sent.getLast().get("data")).get("title"));
        watcher.shutdown();
    }

    @ParameterizedTest
    @CsvSource({"false, false", "true, false", "false, true", "true, true"})
    void unavailableForegroundAtUiaConfirmationInvalidatesHeartbeatSnapshot(
            boolean nullForeground, boolean failedDelivery) {
        Platform platform = new Platform();
        Walker walker = new Walker();
        Probe watcher = new Probe(platform, walker, app -> false);
        watcher.success = !failedDelivery;
        watcher.captureOnce();
        watcher.heartbeatOnce();
        Map<String, Object> first = watcher.sent.getFirst();

        int finalRead = platform.reads + 2;
        platform.onRead = read -> {
            if (read != finalRead) return;
            // The heartbeat already holds its snapshot. Complete another capture
            // before its final foreground read returns the original window again.
            platform.onRead = ignored -> {};
            platform.title = "Interim title";
            walker.onWalk = () -> platform.makeUnavailable(nullForeground);
            watcher.captureOnce();
            walker.onWalk = () -> {};
            platform.restore();
            platform.title = "Title";
        };
        watcher.heartbeatOnce();
        assertEquals(1, watcher.sent.size(), "UIA confirmation must invalidate the copied heartbeat snapshot");
        watcher.heartbeatOnce();
        assertEquals(1, watcher.sent.size(), "Recovery still requires a fresh capture");
        watcher.success = true;
        watcher.captureOnce();
        watcher.heartbeatOnce();
        assertEquals(3, walker.calls);
        assertEquals(2, watcher.sent.size());
        assertEquals(first.get("captureSessionId"), watcher.sent.getLast().get("captureSessionId"));
        if (failedDelivery) assertEquals(first, watcher.sent.getLast());
        else assertNotEquals(first.get("sourceEventId"), watcher.sent.getLast().get("sourceEventId"));
        watcher.shutdown();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void pendingApplicationExcludedWhileForegroundUnavailableIsStillDiscarded(boolean nullForeground) {
        Platform platform = new Platform();
        Walker walker = new Walker();
        AtomicReference<String> excluded = new AtomicReference<>("");
        Probe watcher = new Probe(platform, walker, app -> excluded.get().equalsIgnoreCase(app));
        watcher.success = false;
        watcher.captureOnce();
        watcher.heartbeatOnce();
        Map<String, Object> first = watcher.sent.getFirst();
        platform.makeUnavailable(nullForeground);
        excluded.set("editor.exe");
        watcher.heartbeatOnce();
        assertEquals(1, watcher.sent.size());
        platform.restore();
        excluded.set("");
        watcher.success = true;
        watcher.captureOnce();
        watcher.heartbeatOnce();
        assertEquals(2, watcher.sent.size());
        assertNotEquals(first.get("sourceEventId"), watcher.sent.getLast().get("sourceEventId"));
        assertNotEquals(first.get("captureSessionId"), watcher.sent.getLast().get("captureSessionId"));
        watcher.shutdown();
    }

    @ParameterizedTest
    @CsvSource({"capture, false", "heartbeat, false", "send, false",
            "capture, true", "heartbeat, true", "send, true"})
    void explicitExclusionStillDiscardsAllowedPendingAtEveryCheckpoint(String checkpoint, boolean zeroHandle) {
        Platform platform = new Platform();
        Walker walker = new Walker();
        Probe watcher = new Probe(platform, walker, app -> "private.exe".equalsIgnoreCase(app));
        watcher.success = false;
        watcher.captureOnce();
        watcher.heartbeatOnce();
        Map<String, Object> first = watcher.sent.getFirst();
        Runnable exclude = () -> {
            platform.app = "private.exe";
            if (zeroHandle) platform.handle = 0;
        };
        if (checkpoint.equals("send")) {
            int finalRead = platform.reads + 2;
            platform.onRead = read -> {
                if (read == finalRead) exclude.run();
            };
            watcher.heartbeatOnce();
            platform.onRead = read -> {};
        } else {
            exclude.run();
            if (checkpoint.equals("capture")) watcher.captureOnce();
            else watcher.heartbeatOnce();
        }
        assertEquals(1, watcher.sent.size());
        assertEquals(1, walker.calls);
        platform.app = "editor.exe";
        platform.restore();
        watcher.success = true;
        watcher.captureOnce();
        watcher.heartbeatOnce();
        assertEquals(2, watcher.sent.size());
        assertNotEquals(first.get("sourceEventId"), watcher.sent.getLast().get("sourceEventId"));
        assertNotEquals(first.get("captureSessionId"), watcher.sent.getLast().get("captureSessionId"));
        watcher.shutdown();
    }

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
        String title = "Title";
        long handle = 1;
        boolean nullForeground;
        int reads;
        IntConsumer onRead = read -> {};
        public ForegroundWindow getForegroundWindowInfo() {
            onRead.accept(++reads);
            return nullForeground ? null : new ForegroundWindow(handle, app, title);
        }
        void makeUnavailable(boolean returnNull) {
            handle = 0;
            nullForeground = returnNull;
        }
        void restore() {
            handle = 1;
            nullForeground = false;
        }
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
