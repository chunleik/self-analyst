package com.selfanalyst.document;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 版本化的被动文档结构；不接受可执行模板、公式或资源地址。 */
public record DocumentRequest(DocumentFormat format, String title, JsonNode source,
                              List<Block> blocks, List<Sheet> sheets) {
    public static final int MAX_SOURCE_BYTES = 1024 * 1024;
    public static final int MAX_ROWS = 100_000;
    public static final int MAX_COLUMNS = 64;
    public static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32)
                    .maxStringLength(MAX_SOURCE_BYTES).build()).build());

    public record Block(String type, String text, List<String> items, Sheet table) {}
    public record Sheet(String name, List<String> columns, List<List<JsonNode>> rows) {}

    public static DocumentRequest parse(String format, String title, String json) {
        if (json == null || json.getBytes(StandardCharsets.UTF_8).length > MAX_SOURCE_BYTES)
            throw new IllegalArgumentException("文档结构超过 1 MiB，请缩小内容范围");
        try { return fromNode(DocumentFormat.parse(format), title, JSON.readTree(json)); }
        catch (java.io.IOException e) { throw new IllegalArgumentException("文档结构不是有效的有界 JSON"); }
    }

    public static DocumentRequest fromNode(DocumentFormat format, String title, JsonNode source) {
        if (title == null || title.isBlank() || title.codePointCount(0, title.length()) > 160)
            throw new IllegalArgumentException("文档标题须为 1 至 160 个字符");
        validateText(title);
        fields(source, Set.of("schemaVersion", "blocks", "sheets", "metadata"));
        if (!source.path("schemaVersion").isIntegralNumber() || source.path("schemaVersion").asInt() != 1)
            throw new IllegalArgumentException("不支持的文档 schemaVersion");
        List<Block> blocks = new ArrayList<>();
        List<Sheet> sheets = new ArrayList<>();
        if (source.has("metadata") && !source.get("metadata").isObject())
            throw new IllegalArgumentException("metadata 必须是对象");
        for (JsonNode block : array(source, "blocks")) {
            fields(block, Set.of("type", "text", "items", "table"));
            String type = text(block, "type");
            switch (type) {
                case "heading", "paragraph", "slide" -> {
                    if (block.has("items") || block.has("table")) throw unsupported();
                    blocks.add(new Block(type, text(block, "text"), List.of(), null));
                }
                case "list" -> {
                    if (block.has("text") || block.has("table")) throw unsupported();
                    List<String> items = new ArrayList<>();
                    for (JsonNode item : array(block, "items")) items.add(string(item));
                    if (items.isEmpty()) throw unsupported();
                    blocks.add(new Block(type, "", List.copyOf(items), null));
                }
                case "table" -> {
                    if (block.has("text") || block.has("items")) throw unsupported();
                    blocks.add(new Block(type, "", List.of(), sheet(block.get("table"))));
                }
                default -> throw unsupported();
            }
        }
        for (JsonNode sheet : array(source, "sheets")) sheets.add(sheet(sheet));
        if (blocks.size() > 2_000 || sheets.size() > 32) throw new IllegalArgumentException("文档结构数量超限");
        if (blocks.isEmpty() && sheets.isEmpty()) throw new IllegalArgumentException("文档需要正文或数据表");
        if (format == DocumentFormat.CSV && (!blocks.isEmpty() || sheets.size() != 1))
            throw new IllegalArgumentException("CSV 需要且只支持一个数据表，请使用 sheets");
        if (format == DocumentFormat.XLSX && !blocks.isEmpty())
            throw new IllegalArgumentException("Excel 请使用 sheets，不能静默丢弃正文");
        if (format != DocumentFormat.PPTX && blocks.stream().anyMatch(b -> b.type.equals("slide")))
            throw new IllegalArgumentException("slide 分页标记仅支持 PPTX");
        long rows = sheets.stream().mapToLong(s -> s.rows.size()).sum()
                + blocks.stream().filter(b -> b.table != null).mapToLong(b -> b.table.rows.size()).sum();
        if (rows > MAX_ROWS) throw new IllegalArgumentException("记录超过 100000 条，请缩小范围");
        return new DocumentRequest(format, title, source.deepCopy(), List.copyOf(blocks), List.copyOf(sheets));
    }

    private static Sheet sheet(JsonNode node) {
        fields(node, Set.of("name", "columns", "rows"));
        String name = text(node, "name");
        List<String> columns = new ArrayList<>();
        for (JsonNode column : array(node, "columns")) columns.add(string(column));
        if (columns.isEmpty() || columns.size() > MAX_COLUMNS) throw new IllegalArgumentException("表格需要 1 至 64 列");
        List<List<JsonNode>> rows = new ArrayList<>();
        for (JsonNode row : array(node, "rows")) {
            if (!row.isArray() || row.size() != columns.size()) throw new IllegalArgumentException("数据行列数不一致");
            List<JsonNode> values = new ArrayList<>();
            for (JsonNode value : row) {
                if (value.isTextual()) validateText(value.textValue());
                values.add(value.deepCopy());
            }
            rows.add(List.copyOf(values));
            if (rows.size() > MAX_ROWS) throw new IllegalArgumentException("记录超过 100000 条");
        }
        return new Sheet(name, List.copyOf(columns), List.copyOf(rows));
    }

    private static Iterable<JsonNode> array(JsonNode node, String key) {
        if (!node.has(key)) return List.of();
        if (!node.get(key).isArray()) throw new IllegalArgumentException(key + " 必须是数组");
        return node.get(key);
    }

    private static void fields(JsonNode node, Set<String> allowed) {
        if (node == null || !node.isObject()) throw unsupported();
        node.fieldNames().forEachRemaining(key -> {
            if (!allowed.contains(key)) throw new IllegalArgumentException("不支持的文档字段：" + key);
        });
    }

    private static String text(JsonNode node, String key) { return string(node.get(key)); }
    private static String string(JsonNode node) {
        if (node == null || !node.isTextual()) throw new IllegalArgumentException("文档文字必须是字符串");
        validateText(node.textValue());
        return node.textValue();
    }
    private static void validateText(String value) {
        if (value.codePoints().anyMatch(c -> c < 32 && c != '\n' && c != '\r' && c != '\t'
                || c >= 0xD800 && c <= 0xDFFF || c == 0xFFFE || c == 0xFFFF))
            throw new IllegalArgumentException("文档包含不支持的控制字符");
    }
    private static IllegalArgumentException unsupported() { return new IllegalArgumentException("不支持的文档结构"); }
    public static String display(JsonNode value) {
        return value == null || value.isNull() ? "" : value.isTextual() ? value.textValue() : value.toString();
    }
}
