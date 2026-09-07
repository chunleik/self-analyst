package com.selfanalyst.events.watcher.platform;

public class NoopWindowTracker implements WindowTracker {

    @Override
    public String getActiveApp() {
        return "unknown";
    }

    @Override
    public String getActiveTitle() {
        return "unknown";
    }
}
