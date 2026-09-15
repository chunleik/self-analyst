package com.selfanalyst.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.selfanalyst.config.Config;
import com.selfanalyst.events.raw.RawEventQueryService;
import com.selfanalyst.events.store.EventStore;
import com.selfanalyst.file.FileWatchStore;
import com.selfanalyst.wiki.WikiStore;
import com.selfanalyst.wiki.WikiLevel;
import java.nio.file.Path;
import java.time.*;
import java.util.*;

public final class DocumentDataSources {
    private static final Map<String, List<String>> FIELDS = Map.of(
            "raw", List.of("eventId", "bucketId", "source", "eventTimestamp", "receivedAt", "duration", "data", "schemaVersion"),
            "projection", List.of("id", "timestamp", "duration", "data"),
            "file-metadata", List.of("id", "absolutePath", "relativePath", "watchRoot", "extension", "sizeBytes", "lastModified", "fileCreatedAt"),
            "wiki", List.of("id", "level", "periodStart", "periodEnd", "timezone", "status", "summary", "primaryTask"));
    private final Config config;
    private final EventStore events;
    private final FileWatchStore files;
    private final WikiStore wiki;
    public DocumentDataSources(Config config, EventStore events, FileWatchStore files, WikiStore wiki) {
        this.config = config; this.events = events; this.files = files; this.wiki = wiki;
    }
    public record Query(String source, String bucketId, Instant start, Instant end, String timezone,
                        List<String> fields, String level) {}
    public Query parse(String json) {
        if (json == null || json.length() > 16_384) throw new IllegalArgumentException("导出查询过大");
        try {
            var node = DocumentRequest.JSON.readTree(json);
            if (!node.isObject()) throw new IllegalArgumentException("导出查询必须是对象");
            node.fieldNames().forEachRemaining(key -> {
                if (!Set.of("source", "bucketId", "start", "end", "timezone", "fields", "level").contains(key))
                    throw new IllegalArgumentException("不支持的导出筛选字段：" + key);
            });
            String source = node.path("source").asText();
            if (!FIELDS.containsKey(source)) throw new IllegalArgumentException("不支持的数据来源");
            Instant start = Instant.parse(node.path("start").asText()), end = Instant.parse(node.path("end").asText());
            if (!start.isBefore(end) || Duration.between(start, end).compareTo(Duration.ofDays(config.eventsRawQueryMaxRangeDays())) > 0)
                throw new IllegalArgumentException("导出时间范围无效或超过 " + config.eventsRawQueryMaxRangeDays() + " 天");
            String timezone = node.path("timezone").asText(ZoneId.systemDefault().getId()); ZoneId.of(timezone);
            List<String> fields = new ArrayList<>();
            if (node.has("fields")) {
                if (!node.get("fields").isArray()) throw new IllegalArgumentException("fields 必须是数组");
                for (var field : node.get("fields")) {
                    if (!field.isTextual() || !FIELDS.get(source).contains(field.asText()) || fields.contains(field.asText()))
                        throw new IllegalArgumentException("无效或重复的导出字段");
                    fields.add(field.asText());
                }
                if (fields.isEmpty()) throw new IllegalArgumentException("请选择导出字段");
            } else fields.addAll(FIELDS.get(source));
            String bucket = node.path("bucketId").asText("");
            if ((source.equals("raw") || source.equals("projection")) && bucket.isBlank()) throw new IllegalArgumentException("bucketId 必填");
            if ((source.equals("raw") || source.equals("projection")) && !config.eventsEmbedded()) throw new IllegalArgumentException("本地记录导出在外部事件服务模式不可用");
            String level = node.path("level").asText("");
            if (!level.isEmpty()) WikiLevel.valueOf(level);
            return new Query(source, bucket, start, end, timezone, List.copyOf(fields), level);
        } catch (java.io.IOException | DateTimeException e) { throw new IllegalArgumentException("导出查询或时间格式无效"); }
    }
    public ObjectNode descriptor(Query query) {
        var node = DocumentRequest.JSON.createObjectNode();
        node.put("source", query.source); node.put("bucketId", query.bucketId); node.put("start", query.start.toString());
        node.put("end", query.end.toString()); node.put("timezone", query.timezone); node.put("level", query.level);
        node.set("fields", DocumentRequest.JSON.valueToTree(query.fields)); return node;
    }
    public Prepared prepare(Query query, DocumentFormat format, String title, Path directory, DocumentBudget budget) throws Exception {
        if (format == DocumentFormat.SVG)
            throw new IllegalArgumentException("SVG 不支持直接数据导出，请使用 generate_document 提供矢量源码");
        var spool = new DocumentRowSpool(directory.resolve("rows.part"));
        try {
            java.util.function.Consumer<ObjectNode> consume = row -> {
                budget.check();
                spool.append(query.fields.stream().map(field -> row.path(field)).toList());
            };
            List<String> coverage = new ArrayList<>();
            switch (query.source) {
                case "raw" -> {
                    if (!config.eventsEmbedded()) throw new IllegalArgumentException("原始事件导出不可用");
                    try (var raw = new RawEventQueryService(config.eventsRawDir(), config.eventsRawQueryMaxRangeDays(), config.eventsRawQueryMaxPageSize())) {
                        coverage = raw.exportSnapshot(query.bucketId, query.start, query.end, DocumentRequest.MAX_ROWS, event -> {
                            var row = DocumentRequest.JSON.createObjectNode();
                            row.put("eventId", event.eventId()); row.put("bucketId", event.bucketId()); row.put("source", event.source().storageValue());
                            row.put("eventTimestamp", event.eventTimestamp().toString()); row.put("receivedAt", event.receivedAt().toString());
                            row.put("duration", event.duration()); row.put("schemaVersion", event.schemaVersion());
                            try { row.set("data", DocumentRequest.JSON.readTree(event.canonicalDataJson())); }
                            catch (java.io.IOException e) { throw new IllegalStateException("原始记录格式无效"); }
                            consume.accept(row);
                        }, budget::check);
                    }
                }
                case "projection" -> {
                    if (events == null) throw new IllegalArgumentException("事件投影不可用");
                    events.exportSnapshot(query.bucketId, query.start, query.end, DocumentRequest.MAX_ROWS, event -> {
                        var row = DocumentRequest.JSON.createObjectNode(); row.put("id", event.id()); row.put("timestamp", event.timestamp().toString());
                        row.put("duration", event.duration()); row.set("data", DocumentRequest.JSON.valueToTree(event.data())); consume.accept(row);
                    }, budget::check);
                }
                case "file-metadata" -> {
                    if (files == null) throw new IllegalArgumentException("文件元数据不可用");
                    files.exportSnapshot(query.start, query.end, DocumentRequest.MAX_ROWS, file -> {
                        var row = DocumentRequest.JSON.createObjectNode(); row.put("id", file.id()); row.put("absolutePath", file.absolutePath());
                        row.put("relativePath", file.relativePath()); row.put("watchRoot", file.watchRoot()); row.put("extension", file.extension());
                        row.put("sizeBytes", file.sizeBytes()); row.put("lastModified", Objects.toString(file.lastModified(), null));
                        row.put("fileCreatedAt", Objects.toString(file.fileCreatedAt(), null)); consume.accept(row);
                    }, budget::check);
                }
                case "wiki" -> {
                    if (wiki == null) throw new IllegalArgumentException("Wiki 不可用");
                    wiki.exportSnapshot(query.start, query.end, query.level.isBlank() ? null : WikiLevel.valueOf(query.level), DocumentRequest.MAX_ROWS, entry -> {
                        var row = DocumentRequest.JSON.createObjectNode(); row.put("id", entry.id()); row.put("level", entry.level().name());
                        row.put("periodStart", entry.periodStart().toString()); row.put("periodEnd", entry.periodEnd().toString());
                        row.put("timezone", entry.timezone()); row.put("status", entry.status().name()); row.put("summary", entry.summary());
                        row.put("primaryTask", entry.primaryTask()); consume.accept(row);
                    }, budget::check);
                }
                default -> throw new IllegalArgumentException("不支持的数据来源");
            }
            var source = DocumentRequest.JSON.createObjectNode(); source.put("schemaVersion", 1); source.set("exportQuery", descriptor(query));
            var metadata = source.putObject("metadata"); metadata.setAll(descriptor(query));
            metadata.put("generatedAt", Instant.now().toString()); metadata.put("recordCount", spool.size()); metadata.put("complete", true);
            metadata.set("partitions", DocumentRequest.JSON.valueToTree(coverage));
            if (format == DocumentFormat.CSV) metadata.put("csvEscaping", "电子表格公式前缀添加单引号，原值请使用 JSON");
            return new Prepared(new DocumentRequest(format, title, source, List.of(),
                    List.of(new DocumentRequest.Sheet(title, query.fields, spool))), spool);
        } catch (Exception failure) { spool.close(); throw failure; }
    }
    public record Prepared(DocumentRequest request, AutoCloseable resource) implements AutoCloseable {
        @Override public void close() throws Exception { resource.close(); }
    }
}
