package com.selfanalyst.events.watcher;

import com.selfanalyst.events.model.Event;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class WatcherContinuityTest {
    @Test void startupSleepClockRollbackAndDeliveryFailureResetContinuity() {
        Probe watcher = new Probe();
        watcher.processOnce(); assertEquals(1, watcher.resets);
        watcher.advance(2); watcher.processOnce(); assertEquals(1, watcher.resets);
        watcher.advance(60); watcher.processOnce(); assertEquals(2, watcher.resets);
        watcher.advance(-5); watcher.processOnce(); assertEquals(3, watcher.resets);
        watcher.success = false; watcher.advance(2); watcher.processOnce();
        watcher.success = true; watcher.advance(2); watcher.processOnce(); // 重试原请求
        watcher.advance(2); watcher.processOnce(); assertEquals(4, watcher.resets);
        watcher.stop();
    }
    private static final class Probe extends Watcher {
        Instant now = Instant.parse("2026-09-16T00:00:00Z"); long nanos;
        int resets; boolean success = true;
        Probe() { super("probe", "probe", 2000, "http://127.0.0.1:1"); }
        void advance(int seconds) { now = now.plusSeconds(seconds); nanos += Math.abs(seconds) * 1_000_000_000L; }
        protected Instant observationTime() { return now; }
        protected long observationNanos() { return nanos; }
        protected void resetContinuity(Instant at) { resets++; }
        protected Event collect() { return new Event(now, 1, Map.of("app", "A")); }
        protected boolean sendHeartbeat(Event e, String id) { return success; }
    }
}
