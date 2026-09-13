package com.selfanalyst.document;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class TextDocumentRenderer {
    public void render(DocumentRequest request, OutputStream output, DocumentBudget budget) throws IOException {
        var writer = new OutputStreamWriter(output, StandardCharsets.UTF_8);
        switch (request.format()) {
            case JSON -> {
                if (!request.source().has("exportQuery")) writer.write(DocumentRequest.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(request.source()));
                else {
                    writer.write("{\"metadata\":" + (request.source().path("metadata").isObject()
                            ? request.source().path("metadata").toString() : "{}") + ",\"records\":[");
                    var sheet = request.sheets().getFirst(); boolean first = true;
                    for (var values : sheet.rows()) {
                        budget.check();
                        if (!first) writer.write(','); first = false;
                        var record = DocumentRequest.JSON.createObjectNode();
                        for (int i = 0; i < sheet.columns().size(); i++) record.set(sheet.columns().get(i), values.get(i));
                        writer.write(record.toString());
                    }
                    writer.write("]}");
                }
            }
            case CSV -> {
                writer.write('\uFEFF');
                var sheet = request.sheets().getFirst();
                csvRow(writer, sheet.columns());
                for (var row : sheet.rows()) {
                    budget.check();
                    csvRow(writer, row.stream().map(DocumentRequest::display).toList());
                }
            }
            case MARKDOWN -> {
                writer.write("# " + escape(request.title()) + "\n\n");
                if (request.source().has("metadata")) {
                    writer.write(escape(request.source().get("metadata").toString()) + "\n\n");
                }
                for (var block : request.blocks()) {
                    budget.check();
                    switch (block.type()) {
                        case "heading" -> writer.write("## " + escape(block.text()) + "\n\n");
                        case "paragraph" -> writer.write(escape(block.text()) + "\n\n");
                        case "list" -> {
                            for (String item : block.items()) writer.write("- " + escape(item).replace("\n", "\n  ") + "\n");
                            writer.write('\n');
                        }
                        case "table" -> markdownTable(writer, block.table(), budget);
                        default -> throw new IllegalArgumentException("Markdown 不支持该内容");
                    }
                }
                for (var sheet : request.sheets()) markdownTable(writer, sheet, budget);
            }
            default -> throw new IllegalArgumentException("不是文本格式");
        }
        budget.check();
        writer.flush();
    }

    private static void csvRow(java.io.Writer writer, List<String> values) throws IOException {
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) writer.write(',');
            String value = values.get(i);
            String leading = value.stripLeading();
            if (!leading.isEmpty() && "=+-@".indexOf(leading.charAt(0)) >= 0
                    || value.startsWith("\t") || value.startsWith("\r")) value = "'" + value;
            writer.write('"');
            writer.write(value.replace("\"", "\"\""));
            writer.write('"');
        }
        writer.write("\r\n");
    }

    private static void markdownTable(java.io.Writer writer, DocumentRequest.Sheet sheet, DocumentBudget budget) throws IOException {
        writer.write("## " + escape(sheet.name()) + "\n\n");
        markdownRow(writer, sheet.columns());
        markdownRow(writer, sheet.columns().stream().map(c -> "---").toList());
        for (var row : sheet.rows()) {
            budget.check();
            markdownRow(writer, row.stream().map(DocumentRequest::display).toList());
        }
        writer.write('\n');
    }
    private static void markdownRow(java.io.Writer writer, List<String> row) throws IOException {
        writer.write("| ");
        for (String value : row) writer.write(escape(value).replace("\n", "<br>").replace("\r", "") + " | ");
        writer.write('\n');
    }
    static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\\", "\\\\").replace("*", "\\*").replace("_", "\\_")
                .replace("[", "\\[").replace("]", "\\]").replace("!", "\\!")
                .replace("`", "\\`").replace("|", "\\|").replace("#", "\\#");
    }
}
