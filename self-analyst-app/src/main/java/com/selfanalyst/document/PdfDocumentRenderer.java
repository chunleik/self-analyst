package com.selfanalyst.document;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

public final class PdfDocumentRenderer {
    public void render(DocumentRequest request, OutputStream output, DocumentBudget budget) throws IOException {
        try (var document = new PDDocument(); var fontStream = DocumentFont.stream()) {
            var font = PDType0Font.load(document, fontStream, true);
            try (var layout = new Layout(document, font, budget)) {
                layout.paragraph(request.title(), 22);
                if (request.source().has("metadata")) layout.paragraph(request.source().get("metadata").toString(), 9);
                for (var block : request.blocks()) {
                    budget.check();
                    switch (block.type()) {
                        case "heading" -> layout.paragraph(block.text(), 16);
                        case "paragraph" -> layout.paragraph(block.text(), 11);
                        case "list" -> { for (String item : block.items()) layout.paragraph("• " + item, 11); }
                        case "table" -> layout.table(block.table());
                        default -> throw new IllegalArgumentException("PDF 不支持该内容");
                    }
                }
                for (var sheet : request.sheets()) layout.table(sheet);
            }
            budget.check();
            document.save(output);
        }
    }
    private static final class Layout implements AutoCloseable {
        private static final float LEFT = 42, TOP = 792, BOTTOM = 48, WIDTH = 511;
        private final PDDocument document;
        private final PDType0Font font;
        private final DocumentBudget budget;
        private PDPageContentStream stream;
        private float y;
        Layout(PDDocument document, PDType0Font font, DocumentBudget budget) throws IOException {
            this.document = document; this.font = font; this.budget = budget; page();
        }
        private void page() throws IOException {
            budget.check();
            if (document.getNumberOfPages() >= 200) throw new IllegalArgumentException("PDF 超过 200 页，请缩小范围");
            if (stream != null) stream.close();
            var page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            stream = new PDPageContentStream(document, page);
            text(Integer.toString(document.getNumberOfPages()), LEFT + WIDTH / 2, 25, 9);
            y = TOP;
        }
        private void text(String value, float x, float baseline, float size) throws IOException {
            if (value.isEmpty()) return;
            stream.beginText(); stream.setFont(font, size); stream.newLineAtOffset(x, baseline);
            stream.showText(value); stream.endText();
        }
        void paragraph(String value, float size) throws IOException {
            float leading = size * 1.6f;
            for (String line : DocumentFont.wrap(value, size, WIDTH)) {
                budget.check();
                if (y - leading < BOTTOM) page();
                text(line, LEFT, y - size, size); y -= leading;
            }
            y -= 8;
        }
        void table(DocumentRequest.Sheet sheet) throws IOException {
            if (sheet.columns().size() > 10) throw new IllegalArgumentException("PDF 表格超过 10 列，请选择字段或使用 Excel");
            paragraph(sheet.name(), 14);
            List<List<String>> header = wrapRow(sheet.columns());
            if (rowHeight(header) > (TOP - BOTTOM) / 2) throw new IllegalArgumentException("PDF 表头过长");
            ensure(rowHeight(header) + 20); drawRow(header, 0, lines(header));
            for (var row : sheet.rows()) {
                budget.check();
                List<List<String>> wrapped = wrapRow(row.stream().map(DocumentRequest::display).toList());
                int offset = 0;
                while (offset < lines(wrapped)) {
                    int available = (int) ((y - BOTTOM - 8) / 15);
                    if (available < 1) { page(); drawRow(header, 0, lines(header)); continue; }
                    int take = Math.min(available, lines(wrapped) - offset);
                    drawRow(wrapped, offset, take); offset += take;
                    if (offset < lines(wrapped)) { page(); drawRow(header, 0, lines(header)); }
                }
            }
            y -= 12;
        }
        private List<List<String>> wrapRow(List<String> row) {
            var result = new ArrayList<List<String>>();
            for (String value : row) result.add(DocumentFont.wrap(value, 9, WIDTH / row.size() - 10));
            return result;
        }
        private static int lines(List<List<String>> row) { return row.stream().mapToInt(List::size).max().orElse(1); }
        private static float rowHeight(List<List<String>> row) { return lines(row) * 15 + 8; }
        private void ensure(float height) throws IOException { if (y - height < BOTTOM) page(); }
        private void drawRow(List<List<String>> row, int offset, int count) throws IOException {
            float width = WIDTH / row.size(), height = count * 15 + 8;
            for (int c = 0; c < row.size(); c++) {
                stream.setLineWidth(.4f); stream.addRect(LEFT + c * width, y - height, width, height); stream.stroke();
                for (int i = 0; i < count && offset + i < row.get(c).size(); i++)
                    text(row.get(c).get(offset + i), LEFT + c * width + 5, y - 13 - i * 15, 9);
            }
            y -= height;
        }
        @Override public void close() throws IOException { if (stream != null) stream.close(); }
    }
}
