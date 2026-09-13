package com.selfanalyst.document;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.xslf.usermodel.*;
import org.apache.poi.sl.usermodel.VerticalAlignment;

public final class PresentationDocumentRenderer {
    public void render(DocumentRequest request, OutputStream output, DocumentBudget budget) throws IOException {
        try (var deck = new XMLSlideShow()) {
            deck.setPageSize(new Dimension(960, 540));
            var layout = new Layout(deck, budget);
            layout.slide(request.title());
            if (request.source().has("metadata")) layout.paragraph(request.source().get("metadata").toString());
            for (var block : request.blocks()) {
                budget.check();
                switch (block.type()) {
                    case "slide", "heading" -> layout.slide(block.text());
                    case "paragraph" -> layout.paragraph(block.text());
                    case "list" -> { for (String item : block.items()) layout.paragraph("• " + item); }
                    case "table" -> layout.table(block.table());
                    default -> throw new IllegalArgumentException("PPT 不支持该内容");
                }
            }
            for (var sheet : request.sheets()) layout.table(sheet);
            budget.check(); deck.write(output);
        }
    }
    private static final class Layout {
        private final XMLSlideShow deck;
        private final DocumentBudget budget;
        private XSLFSlide current;
        private String title;
        private double y;
        Layout(XMLSlideShow deck, DocumentBudget budget) { this.deck = deck; this.budget = budget; }
        void slide(String title) {
            budget.check();
            if (deck.getSlides().size() >= 100) throw new IllegalArgumentException("PPT 超过 100 页，请缩小范围");
            var lines = DocumentFont.wrap(title, 28, 850);
            if (lines.size() > 3) throw new IllegalArgumentException("PPT 标题过长，请缩短标题");
            this.title = title;
            current = deck.createSlide();
            var box = current.createTextBox();
            double height = lines.size() * 40 + 8;
            box.setAnchor(new Rectangle2D.Double(48, 28, 864, height));
            text(box, String.join("\n", lines), 28, true);
            y = 40 + height;
            var number = current.createTextBox();
            number.setAnchor(new Rectangle2D.Double(860, 510, 50, 20));
            text(number, Integer.toString(deck.getSlides().size()), 10, false);
        }
        void paragraph(String value) {
            var lines = DocumentFont.wrap(value, 20, 840);
            for (int start = 0; start < lines.size();) {
                budget.check();
                int available = (int) ((490 - y - 12) / 30);
                if (available < 1) { slide(title); continue; }
                int take = Math.min(available, lines.size() - start);
                var box = current.createTextBox();
                box.setAnchor(new Rectangle2D.Double(48, y, 864, take * 30 + 12));
                text(box, String.join("\n", lines.subList(start, start + take)), 20, false);
                y += take * 30 + 16; start += take;
            }
        }
        void table(DocumentRequest.Sheet source) {
            if (source.columns().size() > 8) throw new IllegalArgumentException("PPT 表格超过 8 列，请选择字段或使用 Excel");
            slide(source.name());
            var header = wrap(source.columns());
            if (max(header) > 4) throw new IllegalArgumentException("PPT 表头过长，请缩短列名");
            XSLFTable table = newTable(header);
            for (var row : source.rows()) {
                var wrapped = wrap(row.stream().map(DocumentRequest::display).toList());
                for (int offset = 0; offset < max(wrapped);) {
                    budget.check();
                    int available = (int) ((490 - y - 12) / 22);
                    if (available < 1) { slide(source.name()); table = newTable(header); continue; }
                    int count = Math.min(available, max(wrapped) - offset);
                    addRow(table, wrapped, offset, count, false);
                    offset += count;
                }
            }
        }
        private List<List<String>> wrap(List<String> values) {
            var result = new ArrayList<List<String>>();
            for (String value : values) result.add(DocumentFont.wrap(value, 14, 864.0 / values.size() - 20));
            return result;
        }
        private static int max(List<List<String>> values) { return values.stream().mapToInt(List::size).max().orElse(1); }
        private XSLFTable newTable(List<List<String>> header) {
            var table = current.createTable();
            table.setAnchor(new Rectangle2D.Double(48, y, 864, 1));
            addRow(table, header, 0, max(header), true);
            for (int c = 0; c < header.size(); c++) table.setColumnWidth(c, 864.0 / header.size());
            return table;
        }
        private void addRow(XSLFTable table, List<List<String>> values, int offset, int count, boolean header) {
            var row = table.addRow();
            double height = count * 22 + 12;
            row.setHeight(height);
            for (var lines : values) {
                var cell = row.addCell();
                cell.setVerticalAlignment(VerticalAlignment.TOP);
                cell.setFillColor(header ? new Color(225, 235, 247) : Color.WHITE);
                String value = offset >= lines.size() ? "" : String.join("\n", lines.subList(offset, Math.min(lines.size(), offset + count)));
                text(cell, value, 14, header);
            }
            y += height;
        }
        private static void text(XSLFTextShape shape, String value, double size, boolean bold) {
            shape.clearText(); shape.setWordWrap(true);
            shape.setLeftInset(6); shape.setRightInset(6); shape.setTopInset(4); shape.setBottomInset(4);
            for (String line : value.split("\n", -1)) {
                var paragraph = shape.addNewTextParagraph();
                paragraph.setSpaceAfter(0.0); paragraph.setSpaceBefore(0.0); paragraph.setLineSpacing(-size * 1.5);
                var run = paragraph.addNewTextRun();
                run.setText(line); run.setFontFamily("Noto Sans SC"); run.setFontSize(size);
                run.setBold(bold); run.setFontColor(new Color(30, 45, 65));
            }
        }
    }
}
