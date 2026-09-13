package com.selfanalyst.document;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import org.apache.poi.xwpf.usermodel.*;

public final class WordDocumentRenderer {
    public void render(DocumentRequest request, OutputStream output, DocumentBudget budget) throws IOException {
        try (var doc = new XWPFDocument()) {
            var layout = new Layout(doc, budget);
            layout.paragraph(request.title(), 24, true);
            if (request.source().has("metadata")) layout.paragraph(request.source().get("metadata").toString(), 10, false);
            for (var block : request.blocks()) {
                budget.check();
                switch (block.type()) {
                    case "heading" -> layout.paragraph(block.text(), 16, true);
                    case "paragraph" -> layout.paragraph(block.text(), 11, false);
                    case "list" -> { for (String item : block.items()) layout.paragraph("• " + item, 11, false); }
                    case "table" -> layout.table(block.table());
                    default -> throw new IllegalArgumentException("Word 不支持该内容");
                }
            }
            for (var sheet : request.sheets()) layout.table(sheet);
            doc.write(output);
        }
    }
    private static void run(XWPFRun run, String text, int size, boolean bold) {
        run.setFontFamily("Microsoft YaHei");
        run.setFontFamily("Microsoft YaHei", XWPFRun.FontCharRange.eastAsia);
        run.setFontSize(size);
        run.setBold(bold);
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) run.addBreak();
            run.setText(lines[i]);
        }
    }
    private static final class Layout {
        private final XWPFDocument doc;
        private final DocumentBudget budget;
        private int pages = 1;
        private double remaining = 650;
        Layout(XWPFDocument doc, DocumentBudget budget) {
            this.doc = doc; this.budget = budget;
            var section = doc.getDocument().getBody().addNewSectPr();
            var size = section.addNewPgSz(); size.setW(java.math.BigInteger.valueOf(11906)); size.setH(java.math.BigInteger.valueOf(16838));
            var margin = section.addNewPgMar();
            margin.setLeft(java.math.BigInteger.valueOf(1080)); margin.setRight(java.math.BigInteger.valueOf(1080));
            margin.setTop(java.math.BigInteger.valueOf(1080)); margin.setBottom(java.math.BigInteger.valueOf(1080));
        }
        void page() {
            budget.check();
            if (++pages > 200) throw new IllegalArgumentException("Word 超过 200 页，请缩小范围");
            var paragraph = doc.createParagraph(); paragraph.setPageBreak(true); paragraph.setSpacingAfter(0); paragraph.setSpacingBefore(0);
            remaining = 650;
        }
        void paragraph(String text, int size, boolean bold) {
            var lines = DocumentFont.wrap(text, size, 460);
            for (int offset = 0; offset < lines.size();) {
                budget.check();
                int available = (int) ((remaining - 12) / (size * 1.8));
                if (available < 1) { page(); continue; }
                int count = Math.min(available, lines.size() - offset);
                var paragraph = doc.createParagraph(); paragraph.setSpacingAfter(120);
                paragraph.setSpacingBetween(size * 1.6, LineSpacingRule.EXACT);
                run(paragraph.createRun(), String.join("\n", lines.subList(offset, offset + count)), size, bold);
                remaining -= count * size * 1.8 + 12; offset += count;
            }
        }
        void table(DocumentRequest.Sheet source) {
            if (source.columns().size() > 10) throw new IllegalArgumentException("Word 表格超过 10 列，请选择字段或使用 Excel");
            paragraph(source.name(), 14, true);
            var header = wrap(source.columns());
            if (max(header) > 10) throw new IllegalArgumentException("Word 表头过长，请缩短列名");
            if (remaining < max(header) * 20 + 60) page();
            var table = createTable(header);
            for (var values : source.rows()) {
                var lines = wrap(values.stream().map(DocumentRequest::display).toList());
                for (int offset = 0; offset < max(lines);) {
                    budget.check();
                    int available = (int) ((remaining - 16) / 20);
                    if (available < 1) { page(); table = createTable(header); continue; }
                    int take = Math.min(available, max(lines) - offset);
                    var valuesForRow = new java.util.ArrayList<String>();
                    for (var cell : lines) valuesForRow.add(offset >= cell.size() ? "" : String.join("\n", cell.subList(offset, Math.min(cell.size(), offset + take))));
                    row(table.createRow(), valuesForRow, false);
                    remaining -= take * 20 + 16; offset += take;
                }
            }
        }
        private XWPFTable createTable(List<List<String>> header) {
            var table = doc.createTable(1, header.size()); table.setWidth("100%");
            table.setCellMargins(80, 80, 80, 80);
            table.getRow(0).setRepeatHeader(true);
            row(table.getRow(0), header.stream().map(lines -> String.join("\n", lines)).toList(), true);
            remaining -= max(header) * 20 + 16; return table;
        }
        private List<List<String>> wrap(List<String> values) {
            return values.stream().map(value -> DocumentFont.wrap(value, 10, 460.0 / values.size() - 12)).toList();
        }
        private static int max(List<List<String>> lines) { return lines.stream().mapToInt(List::size).max().orElse(1); }
    }
    private static void row(XWPFTableRow row, List<String> values, boolean header) {
        for (int c = 0; c < values.size(); c++) {
            var cell = row.getCell(c);
            if (header) cell.setColor("E5EDF7");
            row.setCantSplitRow(true);
            cell.getParagraphs().getFirst().setSpacingAfter(0);
            cell.getParagraphs().getFirst().setSpacingBetween(16, LineSpacingRule.EXACT);
            run(cell.getParagraphs().getFirst().createRun(), values.get(c), 10, header);
        }
    }
}
