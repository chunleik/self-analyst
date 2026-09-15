package com.selfanalyst.document;

import com.selfanalyst.config.Config;
import com.selfanalyst.desktop.store.ChatSessionStore;
import com.selfanalyst.events.raw.*;
import com.selfanalyst.file.FileWatchStore;
import com.selfanalyst.wiki.WikiStore;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DocumentExportIntegrationTest {
    @TempDir Path temp;
    @Test void rawExportsAllFormatsWithoutExposingRecordsToAgent() throws Exception {
        var properties = new Properties();
        properties.setProperty("events.mode", "embedded");
        properties.setProperty("memory.dir", temp.resolve("memory").toString());
        properties.setProperty("events.data.dir", temp.resolve("events").toString());
        properties.setProperty("events.raw.dir", temp.resolve("raw").toString());
        var config = com.selfanalyst.config.ConfigResolver.resolve(properties, Map.of()).config();
        var ids = new RawEventIdGenerator();
        Instant start = Instant.parse("2026-09-01T00:00:00Z");
        try (var raw = new RawEventStore(config.eventsRawDir())) {
            for (int i = 0; i < 3; i++) raw.append(RawEvent.create(ids, "source-" + i, "bucket", RawEventSource.WINDOW, 1,
                    RawIngestKind.HEARTBEAT, start.plusSeconds(i), start.plusSeconds(i), 1,
                    Map.of("title", "不应进入模型的原始标题" + i), null, null));
        }
        try (var chats = new ChatSessionStore(config.memoryDir())) {
            var service = new DocumentService(config.memoryDir(), chats);
            service.setDataSources(new DocumentDataSources(config, null, null, null));
            var session = chats.create(new ChatSessionStore.CreateRequest());
            var message = new ChatSessionStore.Message(); message.role = "user"; message.content = "导出";
            String turn = chats.appendMessages(session.id, List.of(message)).getFirst().id;
            String query = "{\"source\":\"raw\",\"bucketId\":\"bucket\",\"start\":\"2026-09-01T00:00:00Z\",\"end\":\"2026-09-01T00:00:02Z\"}";
            for (var format : DocumentFormat.values()) {
                if (format == DocumentFormat.SVG) {
                    assertThrows(IllegalArgumentException.class, () -> service.export(session.id, turn, format.name(), "原始记录", query, null, () -> false));
                    continue;
                }
                var artifact = service.export(session.id, turn, format.name(), "原始记录", query, null, () -> false);
                assertEquals("READY", artifact.status());
                assertEquals(2, artifact.metadata().path("recordCount").asInt());
                assertFalse(DocumentRequest.JSON.writeValueAsString(artifact).contains("不应进入模型"));
                assertFalse(service.readSource(session.id, artifact.id()).contains("不应进入模型"));
                if (format == DocumentFormat.HTML) service.readContent(session.id, artifact.id(), (a, input) -> {
                    var document = org.jsoup.Jsoup.parse(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                    assertEquals(2, document.select("tbody tr").size());
                    assertTrue(document.selectFirst("pre").text().contains("recordCount"));
                    assertTrue(document.text().contains("不应进入模型的原始标题1"));
                });
                if (format == DocumentFormat.JSON) service.readContent(session.id, artifact.id(), (a, input) -> {
                    var body = DocumentRequest.JSON.readTree(input);
                    assertEquals(2, body.get("records").size());
                    assertEquals("不应进入模型的原始标题0", body.at("/records/0/data/title").asText());
                });
            }
            try (var paths = Files.walk(config.memoryDir().resolve("documents"))) {
                assertTrue(paths.noneMatch(p -> p.toString().endsWith(".part")));
            }
        }
    }
    @Test void emptySourcesAndQueryValidationAreExplicit() throws Exception {
        var config = Config.testDefaults(temp);
        try (var files = new FileWatchStore(temp.resolve("files.db")); var wiki = new WikiStore(temp.resolve("wiki.db"))) {
            var sources = new DocumentDataSources(config, null, files, wiki);
            assertThrows(IllegalArgumentException.class, () -> sources.parse("{\"source\":\"raw\",\"bucketId\":\"bucket\",\"start\":\"2026-09-01T00:00:00Z\",\"end\":\"2026-09-02T00:00:00Z\"}"));
            assertThrows(IllegalArgumentException.class, () -> sources.parse("{\"source\":\"raw\"}"));
            for (String source : List.of("file-metadata", "wiki")) {
                var query = sources.parse("{\"source\":\"" + source + "\",\"start\":\"2026-09-01T00:00:00Z\",\"end\":\"2026-09-02T00:00:00Z\"}");
                Path directory = temp.resolve(source); Files.createDirectories(directory);
                try (var prepared = sources.prepare(query, DocumentFormat.JSON, "空结果", directory, new DocumentBudget(() -> false))) {
                    assertTrue(prepared.request().sheets().getFirst().rows().isEmpty());
                    assertTrue(prepared.request().source().at("/metadata/complete").asBoolean());
                }
                try (var prepared = sources.prepare(query, DocumentFormat.HTML, "空结果", directory, new DocumentBudget(() -> false))) {
                    Path target = directory.resolve("empty.html");
                    var budget = new DocumentBudget(() -> false);
                    new DocumentRenderer().render(prepared.request(), target, budget);
                    DocumentFormatVerifier.verify(DocumentFormat.HTML, target, budget);
                    var document = org.jsoup.Jsoup.parse(Files.readString(target));
                    assertEquals(0, document.select("tbody tr").size());
                    assertFalse(document.select("thead th").isEmpty());
                    assertTrue(document.selectFirst("pre").text().contains("\"recordCount\":0"));
                }
            }
        }
    }
    @Test void diskRowsStayBoundedAndPreserveTypes() throws Exception {
        try (var spool = new DocumentRowSpool(temp.resolve("rows.part"))) {
            for (int i = 0; i < 1500; i++) spool.append(List.of(DocumentRequest.JSON.getNodeFactory().numberNode(i),
                    DocumentRequest.JSON.getNodeFactory().textNode("中文," + i)));
            assertEquals(1500, spool.size());
            assertEquals(1499, spool.get(1499).getFirst().intValue());
            assertEquals("中文,0", spool.get(0).get(1).textValue());
        }
        assertFalse(Files.exists(temp.resolve("rows.part")));
    }
}
