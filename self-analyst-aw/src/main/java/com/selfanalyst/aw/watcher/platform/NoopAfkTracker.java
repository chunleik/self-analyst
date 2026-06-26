package com.selfanalyst.aw.watcher.platform;

public class NoopAfkTracker implements AfkTracker {

    @Override
    public long getIdleTimeMillis() {
        return 0;
    }
}
