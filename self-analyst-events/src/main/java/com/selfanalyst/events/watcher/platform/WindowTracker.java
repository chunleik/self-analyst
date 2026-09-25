package com.selfanalyst.events.watcher.platform;

public interface WindowTracker {
    String getActiveApp();
    String getActiveTitle();

    /** True when the foreground process was started as a private-browsing window. */
    default boolean privateBrowsing() { return false; }
}
