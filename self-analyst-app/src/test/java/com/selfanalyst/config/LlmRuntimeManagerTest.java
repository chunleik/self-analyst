package com.selfanalyst.config;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class LlmRuntimeManagerTest {
    private LlmSettings settings(String model) { return new LlmSettings("key", "http://localhost/v1", model, .7, 64); }
    @Test void leasesKeepOldVersionAliveAndCloseExactlyOnce() {
        AtomicInteger closed = new AtomicInteger();
        var runtime = new LlmRuntimeManager<AutoCloseable>(new Object(), settings("old"), ignored -> closed::incrementAndGet);
        var old = runtime.acquire();
        runtime.publish(runtime.prepare(settings("new")));
        assertEquals("old", old.settings().model());
        assertEquals("draining", runtime.status().get("status"));
        assertEquals(0, closed.get());
        try (var current = runtime.acquire()) { assertEquals("new", current.settings().model()); }
        old.close(); old.close();
        assertEquals(1, closed.get());
        assertEquals("applied", runtime.status().get("status"));
        runtime.close(); runtime.close();
        assertEquals(2, closed.get());
        assertThrows(IllegalStateException.class, runtime::acquire);
    }
    @Test void discardedCandidateLeavesCurrentResourceUsable() {
        AtomicInteger closed = new AtomicInteger();
        try (var runtime = new LlmRuntimeManager<AutoCloseable>(new Object(), settings("old"), ignored -> closed::incrementAndGet)) {
            runtime.prepare(settings("discard")).close();
            assertEquals("old", runtime.settings().model());
            assertEquals(1, closed.get());
        }
        assertEquals(2, closed.get());
    }
    @Test void closeDefersResourceDisposalUntilLiveWorkEnds() {
        AtomicInteger closed = new AtomicInteger();
        var runtime = new LlmRuntimeManager<AutoCloseable>(new Object(), settings("old"), ignored -> closed::incrementAndGet);
        var lease = runtime.acquire();
        runtime.close();
        assertEquals(0, closed.get());
        lease.close();
        runtime.awaitIdle();
        assertEquals(1, closed.get());
    }
}
