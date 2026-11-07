package com.selfanalyst.events.watcher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

public class WatcherManager {
    private final List<Watcher> watchers = new ArrayList<>();
    private final String serverUrl;
    private final Predicate<String> captureExcluded;
    private boolean started = false;

    public WatcherManager(String serverUrl) {
        this(serverUrl, app -> false);
    }

    public WatcherManager(String serverUrl, Predicate<String> captureExcluded) {
        this.serverUrl = serverUrl;
        this.captureExcluded = Objects.requireNonNull(captureExcluded);
    }

    public WatcherManager addWindowWatcher() {
        watchers.add(new WindowWatcher(serverUrl, captureExcluded));
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
