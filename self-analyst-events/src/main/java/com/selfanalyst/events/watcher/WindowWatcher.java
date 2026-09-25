package com.selfanalyst.events.watcher;

import com.selfanalyst.events.model.Event;
import com.selfanalyst.events.watcher.platform.LinuxWindowTracker;
import com.selfanalyst.events.watcher.platform.MacWindowTracker;
import com.selfanalyst.events.watcher.platform.NoopWindowTracker;
import com.selfanalyst.events.watcher.platform.WindowTracker;
import com.selfanalyst.events.watcher.platform.WindowsWindowTracker;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public class WindowWatcher extends Watcher {
    private final WindowTracker tracker;
    private String lastApp = "";
    private String lastTitle = "";
    private Instant lastChangeTime = Instant.now();

    public WindowWatcher(String serverUrl) {
        super("watcher-window",
              "watcher-window_" + getHostname(),
              2000, // poll every 2 seconds
              serverUrl);
        this.tracker = createPlatformTracker();
        ensureBucket();
    }

    private WindowTracker createPlatformTracker() {
        String os = System.getProperty("os.name").toLowerCase();
        try {
            if (os.contains("win")) return new WindowsWindowTracker();
            if (os.contains("mac")) return new MacWindowTracker();
            if (os.contains("linux")) return new LinuxWindowTracker();
        } catch (Exception e) {
            log.warn("Failed to init tracker: {}", e.getMessage());
        }
        return new NoopWindowTracker();
    }

    @Override
    protected void resetContinuity(Instant now) {
        lastChangeTime = now;
    }

    @Override
    protected Event collect() {
        try {
            String app = tracker.getActiveApp();
            String title = tracker.getActiveTitle();
            if (app == null) app = "unknown";
            if (title == null) title = "unknown";

            Instant now = Instant.now();
            long durationMs = Math.max(0, Duration.between(lastChangeTime, now).toMillis());
            double duration = durationMs / 1000.0;

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("app", app);
            data.put("title", title);
            if (tracker.privateBrowsing()) data.put("private_browsing", true);
            Event event;
            if (!app.equals(lastApp) || !title.equals(lastTitle)) {
                lastChangeTime = now;
                lastApp = app;
                lastTitle = title;
                event = new Event(now, 0, data);
            } else {
                // Same state - emit heartbeat for current state
                event = new Event(lastChangeTime, duration, data);
            }
            return event;
        } catch (Exception e) {
            return null;
        }
    }
}
