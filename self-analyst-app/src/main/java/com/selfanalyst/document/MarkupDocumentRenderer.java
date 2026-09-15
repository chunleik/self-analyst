package com.selfanalyst.document;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

final class MarkupDocumentRenderer {
    void render(DocumentRequest request, OutputStream output, DocumentBudget budget) throws IOException {
        var writer = new OutputStreamWriter(output, StandardCharsets.UTF_8);
        budget.check();
        if (request.hasMarkupSource()) {
            String content = request.source().get("content").textValue();
            if (request.format() == DocumentFormat.HTML) writer.write(MarkupDocumentVerifier.prepareHtml(content, budget));
            else { MarkupDocumentVerifier.svg(content, budget); writer.write(content); }
        } else if (request.format() == DocumentFormat.HTML) report(request, writer, budget);
        else throw new IllegalArgumentException("SVG 需要专用矢量源码");
        budget.check();
        writer.flush();
    }

    private void report(DocumentRequest request, Writer writer, DocumentBudget budget) throws IOException {
        writer.write("<!DOCTYPE html><html><head>" + MarkupDocumentVerifier.HTML_POLICY
                + "<title>" + escape(request.title()) + "</title><style>"
                + "body{font-family:system-ui,sans-serif;margin:2rem;color:#18212f}"
                + "table{border-collapse:collapse;max-width:100%;margin-bottom:2rem}"
                + "th,td{border:1px solid #ccd3dc;padding:.5rem;text-align:left;white-space:pre-wrap;overflow-wrap:anywhere}"
                + "p,li,pre{white-space:pre-wrap;overflow-wrap:anywhere}"
                + "</style></head><body><h1>" + escape(request.title()) + "</h1>");
        if (request.source().has("metadata")) writer.write("<pre>" + escape(request.source().get("metadata").toString()) + "</pre>");
        for (var block : request.blocks()) {
            budget.check();
            switch (block.type()) {
                case "heading" -> writer.write("<h2>" + escape(block.text()) + "</h2>");
                case "paragraph" -> writer.write("<p>" + escape(block.text()) + "</p>");
                case "list" -> {
                    writer.write("<ul>");
                    for (var item : block.items()) { budget.check(); writer.write("<li>" + escape(item) + "</li>"); }
                    writer.write("</ul>");
                }
                case "table" -> table(writer, block.table(), budget);
                default -> throw new IllegalArgumentException("HTML 不支持该内容");
            }
        }
        for (var sheet : request.sheets()) table(writer, sheet, budget);
        writer.write("</body></html>");
    }

    private void table(Writer writer, DocumentRequest.Sheet sheet, DocumentBudget budget) throws IOException {
        writer.write("<h2>" + escape(sheet.name()) + "</h2><table><thead><tr>");
        for (var column : sheet.columns()) writer.write("<th>" + escape(column) + "</th>");
        writer.write("</tr></thead><tbody>");
        for (var row : sheet.rows()) {
            budget.check(); writer.write("<tr>");
            for (var value : row) writer.write("<td>" + escape(DocumentRequest.display(value)) + "</td>");
            writer.write("</tr>");
        }
        writer.write("</tbody></table>");
    }

    static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
