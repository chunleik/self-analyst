package com.selfanalyst.events.watcher;

import com.selfanalyst.events.model.Event;
import com.selfanalyst.events.watcher.platform.LinuxWindowTracker;
import com.selfanalyst.events.watcher.platform.MacWindowTracker;
import com.selfanalyst.events.watcher.platform.NoopWindowTracker;
import com.selfanalyst.events.watcher.platform.WindowTracker;
import com.selfanalyst.events.watcher.platform.WindowsWindowTracker;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

public class WindowWatcher extends Watcher {
    private final WindowTracker tracker;
    private final Predicate<String> captureExcluded;
    private String lastApp = "";
    private String lastTitle = "";
    private Instant lastChangeTime = Instant.now();

    public WindowWatcher(String serverUrl) {
        this(serverUrl, app -> false);
    }

    public WindowWatcher(String serverUrl, Predicate<String> captureExcluded) {
        this(serverUrl, captureExcluded, null);
        ensureBucket();
    }

    WindowWatcher(String serverUrl, Predicate<String> captureExcluded, WindowTracker tracker) {
        super("watcher-window",
              "watcher-window_" + getHostname(),
              2000, // poll every 2 seconds
              serverUrl);
        this.captureExcluded = Objects.requireNonNull(captureExcluded);
        this.tracker = tracker != null ? tracker : createPlatformTracker();
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
        lastApp = "";
        lastTitle = "";
    }

    @Override
    protected boolean heartbeatAllowed(Event event) {
        return !captureExcluded.test((String) event.data().get("app"))
                && !captureExcluded.test(Objects.requireNonNullElse(tracker.getActiveApp(), "unknown"));
    }

    @Override
    protected Event collect() {
        try {
            String app = tracker.getActiveApp();
            if (app == null) app = "unknown";
            if (captureExcluded.test(app)) {
                resetContinuity(observationTime());
                return null;
            }
            String title = tracker.getActiveTitle();
            if (title == null) title = "unknown";

            Instant now = observationTime();
            long durationMs = Math.max(0, Duration.between(lastChangeTime, now).toMillis());
            double duration = durationMs / 1000.0;

            Event event;
            if (!app.equals(lastApp) || !title.equals(lastTitle)) {
                lastChangeTime = now;
                lastApp = app;
                lastTitle = title;
                event = new Event(now, 0, Map.of("app", (Object) app, "title", (Object) title));
            } else {
                // Same state - emit heartbeat for current state
                event = new Event(lastChangeTime, duration,
                    Map.of("app", (Object) app, "title", (Object) title));
            }
            return event;
        } catch (Exception e) {
            return null;
        }
    }
}
