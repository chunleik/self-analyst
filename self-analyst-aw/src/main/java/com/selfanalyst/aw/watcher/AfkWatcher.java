package com.selfanalyst.aw.watcher;

import com.selfanalyst.aw.model.Event;
import com.selfanalyst.aw.watcher.platform.AfkTracker;
import com.selfanalyst.aw.watcher.platform.LinuxAfkTracker;
import com.selfanalyst.aw.watcher.platform.MacAfkTracker;
import com.selfanalyst.aw.watcher.platform.NoopAfkTracker;
import com.selfanalyst.aw.watcher.platform.WindowsAfkTracker;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

public class AfkWatcher extends Watcher {
    private final AfkTracker tracker;
    private boolean lastAfk = false;
    private Instant lastChangeTime = Instant.now();
    private static final long AFK_THRESHOLD_MS = 180_000; // 3 minutes

    public AfkWatcher(String serverUrl) {
        super("aw-watcher-afk",
              "aw-watcher-afk_" + getHostname(),
              2000,
              serverUrl);
        this.tracker = createPlatformTracker();
        ensureBucket();
    }

    private AfkTracker createPlatformTracker() {
        String os = System.getProperty("os.name").toLowerCase();
        try {
            if (os.contains("win")) return new WindowsAfkTracker();
            if (os.contains("mac")) return new MacAfkTracker();
            if (os.contains("linux")) return new LinuxAfkTracker();
        } catch (Exception e) {
            log.warn("Failed to init tracker: {}", e.getMessage());
        }
        return new NoopAfkTracker();
    }

    @Override
    protected Event collect() {
        try {
            long idleMs = tracker.getIdleTimeMillis();
            boolean isAfk = idleMs >= AFK_THRESHOLD_MS;
            Instant now = Instant.now();
            long durationMs = Duration.between(lastChangeTime, now).toMillis();
            double duration = durationMs / 1000.0;

            Event event;
            if (isAfk != lastAfk) {
                event = new Event(lastChangeTime, duration,
                    Map.of("status", (Object) (lastAfk ? "afk" : "not-afk")));
                lastChangeTime = now;
                lastAfk = isAfk;
            } else {
                event = new Event(lastChangeTime, duration,
                    Map.of("status", (Object) (isAfk ? "afk" : "not-afk")));
            }
            return event.duration() > 0 ? event : null;
        } catch (Exception e) {
            return null;
        }
    }
}
