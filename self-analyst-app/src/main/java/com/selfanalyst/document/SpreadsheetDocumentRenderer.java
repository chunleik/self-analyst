package com.selfanalyst.document;

import java.io.IOException;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Locale;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.WorkbookUtil;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

public final class SpreadsheetDocumentRenderer {
    public void render(DocumentRequest request, OutputStream output, DocumentBudget budget) throws IOException {
        try (var book = new SXSSFWorkbook(100)) {
            book.setCompressTempFiles(true);
            var font = book.createFont();
            font.setFontName("Microsoft YaHei");
            var body = book.createCellStyle();
            body.setFont(font);
            body.setWrapText(true);
            var heading = book.createCellStyle();
            heading.cloneStyleFrom(body);
            heading.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());
            heading.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            var names = new HashSet<String>();
            for (var source : request.sheets()) {
                budget.check();
                String name = WorkbookUtil.createSafeSheetName(source.name());
                if (name == null || name.isBlank()) name = "数据";
                String base = name;
                for (int n = 2; !names.add(name.toLowerCase(Locale.ROOT)); n++) {
                    String suffix = "_" + n;
                    name = base.substring(0, Math.min(base.length(), 31 - suffix.length())) + suffix;
                }
                var sheet = book.createSheet(name);
                sheet.createFreezePane(0, 1);
                var header = sheet.createRow(0);
                for (int c = 0; c < source.columns().size(); c++) {
                    putText(header.createCell(c), source.columns().get(c));
                    header.getCell(c).setCellStyle(heading);
                    sheet.setColumnWidth(c, 24 * 256);
                }
                int index = 1;
                for (var row : source.rows()) {
                    budget.check();
                    var target = sheet.createRow(index++);
                    for (int c = 0; c < row.size(); c++) {
                        var cell = target.createCell(c);
                        cell.setCellStyle(body);
                        var value = row.get(c);
                        if (value.isBoolean()) cell.setCellValue(value.booleanValue());
                        else if (value.isNumber() && value.decimalValue().precision() <= 15
                                && Double.isFinite(value.doubleValue())) cell.setCellValue(value.doubleValue());
                        else if (!value.isNull()) putText(cell, DocumentRequest.display(value));
                    }
                }
            }
            if (request.source().has("metadata")) {
                String name = "说明";
                while (!names.add(name.toLowerCase(Locale.ROOT))) name += "_";
                var info = book.createSheet(name);
                int[] index = {0};
                request.source().get("metadata").fields().forEachRemaining(entry -> {
                    var row = info.createRow(index[0]++);
                    putText(row.createCell(0), entry.getKey());
                    putText(row.createCell(1), DocumentRequest.display(entry.getValue()));
                });
            }
            budget.check();
            book.write(output);
        }
    }
    private static void putText(Cell cell, String value) {
        if (value.length() > 32767) throw new IllegalArgumentException("单元格文字超过 Excel 限制，请改用 JSON");
        cell.setCellValue(value);
    }
}
