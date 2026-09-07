package com.selfanalyst.events.watcher.platform;

public class NoopAfkTracker implements AfkTracker {

    @Override
    public long getIdleTimeMillis() {
        return 0;
    }
}
