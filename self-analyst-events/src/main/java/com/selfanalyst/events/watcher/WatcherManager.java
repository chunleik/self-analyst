package com.selfanalyst.events.watcher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class WatcherManager {
    private final List<Watcher> watchers = new ArrayList<>();
    private final String serverUrl;
    private boolean started = false;

    public WatcherManager(String serverUrl) {
        this.serverUrl = serverUrl;
    }

    public WatcherManager addWindowWatcher() {
        watchers.add(new WindowWatcher(serverUrl));
        return this;
    }

    public WatcherManager addAfkWatcher() {
        watchers.add(new AfkWatcher(serverUrl));
        return this;
    }

    public void startAll() {
        if (started) return;
        watchers.forEach(Watcher::start);
        started = true;
    }

    public void stopAll() {
        watchers.forEach(Watcher::stop);
        started = false;
    }

    public List<Watcher> getWatchers() {
        return Collections.unmodifiableList(watchers);
    }
}
