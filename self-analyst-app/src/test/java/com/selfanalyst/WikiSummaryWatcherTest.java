package com.selfanalyst;

import com.selfanalyst.wiki.WikiLevel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WikiSummaryWatcherTest {

    @Test
    void timelineBucketsUseWikiPrefixNotWatcher() {
        assertEquals("wiki", WikiSummaryWatcher.CLIENT);
        String hourly = WikiSummaryWatcher.timelineBucketId(WikiLevel.HOUR, "testhost");
        String halfday = WikiSummaryWatcher.timelineBucketId(WikiLevel.HALF_DAY, "testhost");
        String daily = WikiSummaryWatcher.timelineBucketId(WikiLevel.DAY, "testhost");
        assertEquals("wiki-hourly_testhost", hourly);
        assertEquals("wiki-halfday_testhost", halfday);
        assertEquals("wiki-daily_testhost", daily);
        assertFalse(hourly.startsWith("watcher-"));
        assertFalse(halfday.startsWith("aw-watcher-"));
        assertTrue(daily.startsWith("wiki-"));
    }
}
