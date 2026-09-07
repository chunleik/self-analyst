package com.selfanalyst.events.watcher;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WatcherIdentifierTest {

    @Test
    void windowAndAfkWatchersWriteProductBucketIds() {
        WindowWatcher window = new WindowWatcher("http://127.0.0.1:1");
        AfkWatcher afk = new AfkWatcher("http://127.0.0.1:1");
        try {
            assertEquals("watcher-window", window.name());
            assertTrue(window.bucketId().startsWith("watcher-window_"));
            assertFalse(window.bucketId().startsWith("aw-watcher-"));

            assertEquals("watcher-afk", afk.name());
            assertTrue(afk.bucketId().startsWith("watcher-afk_"));
            assertFalse(afk.bucketId().startsWith("aw-watcher-"));
        } finally {
            window.stop();
            afk.stop();
        }
    }
}
