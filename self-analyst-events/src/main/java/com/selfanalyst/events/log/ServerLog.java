package com.selfanalyst.events.log;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ServerLog {

    private final int maxSize;
    private final List<String> entries;

    public ServerLog(int maxSize) {
        this.maxSize = maxSize;
        this.entries = new ArrayList<>();
        info("ServerLog initialized with max " + maxSize + " entries");
    }

    public void info(String message) {
        addEntry("INFO", message);
    }

    public void warn(String message) {
        addEntry("WARN", message);
    }

    public void error(String message) {
        addEntry("ERROR", message);
    }

    private void addEntry(String level, String message) {
        String entry = "[" + Instant.now() + "] [" + level + "] " + message;
        synchronized (entries) {
            entries.add(entry);
            while (entries.size() > maxSize) {
                entries.removeFirst();
            }
        }
    }

    public List<String> getEntries() {
        synchronized (entries) {
            return Collections.unmodifiableList(new ArrayList<>(entries));
        }
    }
}
