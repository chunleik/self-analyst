package com.selfanalyst.document;

import com.selfanalyst.config.ConfigResolver;
import com.selfanalyst.events.model.*;
import com.selfanalyst.events.store.*;
import com.selfanalyst.file.FileWatchStore;
import com.selfanalyst.wiki.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DocumentDataSourceTest {
    @TempDir Path temp;
    @Test void projectionExportFiltersWithOneSnapshotAndCountsOnlyPublishedRows() throws Exception {
        Instant start = Instant.parse("2026-11-01T00:00:00Z"), end = start.plusSeconds(3600);
        var config = ConfigResolver.resolve(new Properties(), Map.of()).config();
        var privacy = new com.selfanalyst.config.PrivacyRuntime("capture.exe", "hidden.exe", "private.test");
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        try (var db = new Database(temp.resolve("privacy-events"))) {
            var events = new EventStore(db, PulseTimeConfig.DEFAULT);
            new BucketStore(db).create(Bucket.create("bucket", "Synthetic", "currentwindow", "test", "test"));
            for (var data : List.of(Map.<String, Object>of("app", "capture.exe"),
                    Map.<String, Object>of("app", "HIDDEN.EXE"),
                    Map.<String, Object>of("app", "browser.exe", "url_host", "sub.private.test"),
                    Map.<String, Object>of("app", "browser.exe", "context_title", "private.test article"),
                    Map.<String, Object>of("app", "browser.exe", "private_browsing", true),
                    Map.<String, Object>of("app", "visible.exe", "title", "visible"))) {
                events.insertEvent("bucket", new Event(start, 10, data));
            }
            var sources = new DocumentDataSources(config, events, null, null,
                    () -> { reads.incrementAndGet(); return privacy.modelPolicy().get(); });
            var query = sources.parse("{\"source\":\"projection\",\"bucketId\":\"bucket\",\"start\":\"" + start
                    + "\",\"end\":\"" + end + "\",\"fields\":[\"data\"]}");
            for (int index = 0; index < 2; index++) {
                Path directory = Files.createDirectories(temp.resolve("export-" + index));
                try (var prepared = sources.prepare(query, DocumentFormat.JSON, "Synthetic export", directory, new DocumentBudget(() -> false))) {
                    var rows = prepared.request().sheets().getFirst().rows();
                    assertEquals(index == 0 ? 1 : 5, rows.size());
                    if (index == 0) assertEquals("visible.exe", rows.getFirst().getFirst().path("app").asText());
                    var metadata = prepared.request().source().path("metadata");
                    assertEquals(rows.size(), metadata.path("recordCount").asInt());
                    assertEquals(index == 0 ? 5 : 1, metadata.path("privacyFilteredCount").asInt());
                    assertTrue(metadata.path("complete").asBoolean());
                }
                assertEquals(index + 1, reads.get());
                privacy.apply("", "", "");
            }
        }
    }

    @Test void typedSnapshotsPreserveRecordsAndHalfOpenBounds() throws Exception {
        Instant start = Instant.parse("2026-09-01T00:00:00Z"), end = start.plusSeconds(3600);
        var properties = new Properties(); properties.setProperty("events.mode", "embedded");
        var config = ConfigResolver.resolve(properties, Map.of()).config();
        try (var db = new Database(temp.resolve("events")); var files = new FileWatchStore(temp.resolve("files.db")); var wiki = new WikiStore(temp.resolve("wiki.db"))) {
            var events = new EventStore(db, PulseTimeConfig.DEFAULT);
            new BucketStore(db).create(Bucket.create("bucket", "测试", "currentwindow", "test", "test"));
            events.insertEvent("bucket", new Event(0, start, 12.5, Map.of("title", "合成投影")));
            events.insertEvent("bucket", new Event(0, start.plusNanos(1), 12.75, Map.of("title", "纳秒记录")));
            events.insertEvent("bucket", new Event(0, end, 2, Map.of("title", "边界之外")));
            files.upsertPending("D:/合成/报告.docx", "报告.docx", "D:/合成", "docx");
            files.updateCollected("D:/合成/报告.docx", 1234, start, start);
            files.upsertPending("D:/合成/纳秒.docx", "纳秒.docx", "D:/合成", "docx");
            files.updateCollected("D:/合成/纳秒.docx", 321, start, start.plusNanos(1));
            files.upsertPending("D:/合成/边界.docx", "边界.docx", "D:/合成", "docx");
            files.updateCollected("D:/合成/边界.docx", 2345, start, end);
            wiki.upsert(new WikiEntry("day", WikiLevel.DAY, start, end, "Asia/Shanghai", WikiStatus.SUMMARIZED,
                    "合成报告", "测试", List.of(), null, List.of(), "mock", "v1", 0, null, null, start, end, end));
            var sources = new DocumentDataSources(config, events, files, wiki);
            for (String source : List.of("projection", "file-metadata", "wiki")) {
                String json = "{\"source\":\"" + source + "\",\"bucketId\":\"bucket\",\"start\":\"" + start + "\",\"end\":\"" + end + "\",\"timezone\":\"Asia/Shanghai\"}";
                Path directory = temp.resolve(source); Files.createDirectories(directory);
                try (var prepared = sources.prepare(sources.parse(json), DocumentFormat.JSON, "来源验收", directory, new DocumentBudget(() -> false))) {
                    var sheet = prepared.request().sheets().getFirst();
                    assertEquals(source.equals("wiki") ? 1 : 2, sheet.rows().size());
                    if (source.equals("projection")) assertEquals(12.5, sheet.rows().getFirst().get(2).doubleValue());
                    if (source.equals("file-metadata")) assertEquals(1234, sheet.rows().getFirst().get(5).longValue());
                    if (source.equals("wiki")) assertEquals("合成报告", sheet.rows().getFirst().get(6).textValue());
                    assertEquals(source, prepared.request().source().at("/metadata/source").asText());
                }
            }
        }
    }
}
