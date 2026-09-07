package com.selfanalyst.events.store;

import com.selfanalyst.events.model.Bucket;
import com.selfanalyst.events.model.Event;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ContentEventPolicyTest {

    @TempDir
    Path tempDir;

    @Test
    void acceptsOnlyTitleEventV2Fields() {
        Map<String, Object> data = validTitleData();

        assertDoesNotThrow(() -> ContentEventPolicy.validate(
                "watcher-content_test", "watcher-content", data));
        assertDoesNotThrow(() -> ContentEventPolicy.validate(
                "aw-watcher-content_test", "aw-watcher-content", data));
    }

    @Test
    void rejectsRawOrUnknownContentFieldsWithoutEchoingValues() {
        Map<String, Object> data = validTitleData();
        data.put("text_content", "SELF_ANALYST_FORBIDDEN_BODY_7F3A");

        ContentEventPolicyViolationException error = assertThrows(
                ContentEventPolicyViolationException.class,
                () -> ContentEventPolicy.validate(
                        "aw-watcher-content_test", "aw-watcher-content", data));

        assertEquals("text_content", error.field());
        org.junit.jupiter.api.Assertions.assertFalse(
                error.getMessage().contains("SELF_ANALYST_FORBIDDEN_BODY_7F3A"));
    }

    @Test
    void rejectsBodyTextSmuggledThroughAllowedTitleField() {
        Map<String, Object> multiline = validTitleData();
        multiline.put("title", "标题\n消息正文");
        assertThrows(ContentEventPolicyViolationException.class,
                () -> ContentEventPolicy.validate(
                        "watcher-content_test", "watcher-content", multiline));

        Map<String, Object> oversized = validTitleData();
        oversized.put("title", "文".repeat(1025));
        assertThrows(ContentEventPolicyViolationException.class,
                () -> ContentEventPolicy.validate(
                        "watcher-content_test", "watcher-content", oversized));
    }

    @Test
    void doesNotEchoUntrustedUnknownFieldNames() {
        Map<String, Object> data = validTitleData();
        data.put("SELF_ANALYST_FORBIDDEN_BODY_7F3A", "value");

        ContentEventPolicyViolationException error = assertThrows(
                ContentEventPolicyViolationException.class,
                () -> ContentEventPolicy.validate(
                        "aw-watcher-content_test", "aw-watcher-content", data));

        assertEquals("unknown", error.field());
        org.junit.jupiter.api.Assertions.assertFalse(
                error.getMessage().contains("SELF_ANALYST_FORBIDDEN_BODY_7F3A"));
    }

    @Test
    void rejectsSemanticallyInvalidContextTitleAtServerBoundary() {
        for (String invalid : java.util.List.of(
                "https://example.com/body", "file:///C:/private/message.txt",
                "搜索", "Search", "第一句。第二句。")) {
            Map<String, Object> data = validTitleData();
            data.put("context_title", invalid);
            assertThrows(ContentEventPolicyViolationException.class,
                    () -> ContentEventPolicy.validate(
                            "watcher-content_test", "watcher-content", data), invalid);
        }
    }

    @Test
    void eventStoreEnforcesPolicyByPrefixAndClientButLeavesAudioUntouched() throws Exception {
        try (Database db = new Database(tempDir)) {
            BucketStore buckets = new BucketStore(db);
            EventStore events = new EventStore(db, PulseTimeConfig.DEFAULT);
            buckets.create(Bucket.create(
                    "custom-content", "Custom", "listening", "aw-watcher-content", "test"));
            buckets.create(Bucket.create(
                    "aw-watcher-audio_test", "Audio", "audio", "audio", "test"));

            Map<String, Object> forbidden = validTitleData();
            forbidden.put("text_content", "secret");
            assertThrows(ContentEventPolicyViolationException.class,
                    () -> events.insertEvent("watcher-content_test",
                            new Event(Instant.now(), 1, forbidden)));
            assertThrows(ContentEventPolicyViolationException.class,
                    () -> events.insertEvent("custom-content",
                            new Event(Instant.now(), 1, forbidden)));

            assertDoesNotThrow(() -> events.insertEvent(
                    "aw-watcher-audio_test",
                    new Event(Instant.now(), 1, Map.of("text", "audio transcript"))));
            assertEquals(0, events.countByBucket("watcher-content_test"));
            assertEquals(0, events.countByBucket("custom-content"));
            assertEquals(1, events.countByBucket("aw-watcher-audio_test"));
        }
    }

    private static Map<String, Object> validTitleData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("schema_version", 2);
        data.put("app", "Weixin.exe");
        data.put("title", "微信");
        data.put("context_title", "项目讨论群");
        data.put("context_kind", "chat");
        data.put("title_source", "uia_context");
        data.put("title_confidence", "high");
        data.put("uia_chars", 4280);
        return data;
    }
}
