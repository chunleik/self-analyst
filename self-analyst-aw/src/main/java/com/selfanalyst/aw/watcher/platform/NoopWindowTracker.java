package com.selfanalyst.aw.watcher.platform;

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
