package com.selfanalyst.aw.watcher;

import com.selfanalyst.aw.model.Event;
import com.selfanalyst.aw.watcher.platform.LinuxWindowTracker;
import com.selfanalyst.aw.watcher.platform.MacWindowTracker;
import com.selfanalyst.aw.watcher.platform.NoopWindowTracker;
import com.selfanalyst.aw.watcher.platform.WindowTracker;
import com.selfanalyst.aw.watcher.platform.WindowsWindowTracker;

import java.time.Duration;
import java.time.Instant;
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
    protected Event collect() {
        try {
            String app = tracker.getActiveApp();
            String title = tracker.getActiveTitle();
            if (app == null) app = "unknown";
            if (title == null) title = "unknown";

            Instant now = Instant.now();
            long durationMs = Duration.between(lastChangeTime, now).toMillis();
            double duration = durationMs / 1000.0;

            Event event;
            if (!app.equals(lastApp) || !title.equals(lastTitle)) {
                // State changed - emit event for the previous state
                event = new Event(lastChangeTime, duration,
                    Map.of("app", (Object) lastApp, "title", (Object) lastTitle));
                lastChangeTime = now;
                lastApp = app;
                lastTitle = title;
            } else {
                // Same state - emit heartbeat for current state
                event = new Event(lastChangeTime, duration,
                    Map.of("app", (Object) app, "title", (Object) title));
            }
            return event.duration() > 0 ? event : null;
        } catch (Exception e) {
            return null;
        }
    }
}
