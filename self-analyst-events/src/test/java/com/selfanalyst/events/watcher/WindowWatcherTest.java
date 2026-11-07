package com.selfanalyst.events.watcher;

import com.selfanalyst.events.model.Event;
import com.selfanalyst.events.watcher.platform.WindowTracker;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

class WindowWatcherTest {
    @Test void excludedCollectReturnsNullAndSwitchBackStartsFresh() {
        Tracker tracker = new Tracker();
        Probe watcher = new Probe(tracker, app -> "private.exe".equalsIgnoreCase(app));
        watcher.processOnce();
        watcher.advance(); watcher.processOnce();
        assertEquals(2, watcher.sent.getLast().duration());
        tracker.app = "PRIVATE.EXE";
        watcher.advance(); assertNull(watcher.collect());
        watcher.processOnce();
        assertEquals(2, watcher.sent.size());
        tracker.app = "editor.exe";
        watcher.advance(); watcher.processOnce();
        assertEquals(0, watcher.sent.getLast().duration());
        assertEquals(watcher.now, watcher.sent.getLast().timestamp());
        watcher.stop();
    }

    @Test void fullNameMatchingAndDynamicPredicateApplyNextRound() {
        Tracker tracker = new Tracker(); tracker.app = "PotPlayerMini64.exe";
        AtomicReference<String> excluded = new AtomicReference<>("PotPlayer.exe");
        Probe watcher = new Probe(tracker, app -> excluded.get().equalsIgnoreCase(app));
        watcher.processOnce(); assertEquals(1, watcher.sent.size());
        excluded.set("POTPLAYERMINI64.EXE");
        watcher.advance(); watcher.processOnce(); assertEquals(1, watcher.sent.size());
        excluded.set("");
        watcher.advance(); watcher.processOnce();
        assertEquals(2, watcher.sent.size());
        assertEquals(0, watcher.sent.getLast().duration());
        watcher.stop();
    }

    @Test void newlyExcludedPendingRetryIsDiscardedEvenAfterForegroundSwitches() {
        Tracker tracker = new Tracker();
        AtomicReference<String> excluded = new AtomicReference<>("");
        Probe watcher = new Probe(tracker, app -> excluded.get().equalsIgnoreCase(app));
        watcher.success = false; watcher.processOnce();
        excluded.set("editor.exe"); tracker.app = "other.exe";
        watcher.advance(); watcher.processOnce();
        assertEquals(1, watcher.sent.size());
        watcher.success = true; watcher.advance(); watcher.processOnce();
        assertEquals("other.exe", watcher.sent.getLast().data().get("app"));
        assertEquals(0, watcher.sent.getLast().duration());
        watcher.stop();
    }

    @Test void excludedForegroundBlocksAnAllowedPendingRetry() {
        Tracker tracker = new Tracker();
        Probe watcher = new Probe(tracker, app -> "private.exe".equalsIgnoreCase(app));
        watcher.success = false; watcher.processOnce();
        tracker.app = "private.exe";
        watcher.advance(); watcher.processOnce();
        assertEquals(1, watcher.sent.size());
        tracker.app = "editor.exe"; watcher.success = true;
        watcher.advance(); watcher.processOnce();
        assertEquals(0, watcher.sent.getLast().duration());
        watcher.stop();
    }

    private static final class Tracker implements WindowTracker {
        String app = "editor.exe";
        public String getActiveApp() { return app; }
        public String getActiveTitle() { return "Same title"; }
    }
    private static final class Probe extends WindowWatcher {
        Instant now = Instant.parse("2026-11-07T00:00:00Z");
        long nanos;
        boolean success = true;
        final List<Event> sent = new ArrayList<>();
        Probe(WindowTracker tracker, Predicate<String> excluded) {
            super("http://127.0.0.1:1", excluded, tracker);
        }
        void advance() { now = now.plusSeconds(2); nanos += 2_000_000_000L; }
        protected Instant observationTime() { return now; }
        protected long observationNanos() { return nanos; }
        protected boolean sendHeartbeat(Event event, String id) { sent.add(event); return success; }
    }
}
