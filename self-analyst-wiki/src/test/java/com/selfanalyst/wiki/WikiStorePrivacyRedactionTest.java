package com.selfanalyst.wiki;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WikiStorePrivacyRedactionTest {

    @TempDir Path dir;

    @Test
    void redactsExistingSummarizedNarrativesOnce() {
        try (WikiStore store = new WikiStore(dir.resolve("wiki.db"))) {
            Instant start = Instant.parse("2026-09-01T04:00:00Z");
            Instant end = start.plusSeconds(3600);
            var metrics = new WikiEntry.WikiMetrics(60, 0, 1, List.of(new WikiEntry.AppDuration("Zoom.exe", 60)), Map.of());
            var task = new WikiEntry.TaskSegment("评审", "参加会议号：361 881 114", List.of(),
                    List.of("Zoom.exe"), "medium", List.of("f1"), "inferred");
            store.upsert(new WikiEntry("hour-a", WikiLevel.HOUR, start, end, "UTC", WikiStatus.SUMMARIZED,
                    "登录 10.2.3.4 后台", "后台", List.of(task), metrics, List.of(), "model", "v9",
                    0, null, null, start, end, end, "facts-v6", "events-v2", Map.of()));

            assertEquals(1, store.redactPrivacy(WikiPrivacyPolicy.none(), 10));
            WikiEntry rewritten = store.query(start, end, WikiLevel.HOUR).getFirst();
            assertTrue(rewritten.summary().contains("[内网地址]"));
            assertFalse(rewritten.summary().contains("10.2.3.4"));
            assertTrue(rewritten.taskSegments().getFirst().summary().contains("会议号：[已隐藏]"));
            assertEquals(60, rewritten.metrics().activeSeconds());
            assertTrue(rewritten.metrics().extra().containsKey("privacyRedactedAt"));
            assertEquals(0, store.redactPrivacy(WikiPrivacyPolicy.none(), 10));
        }
    }
}
