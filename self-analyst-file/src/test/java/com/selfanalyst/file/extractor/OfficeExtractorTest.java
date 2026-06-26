package com.selfanalyst.file.extractor;

import org.apache.poi.xslf.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Rectangle;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class OfficeExtractorTest {

    private final OfficeExtractor extractor = new OfficeExtractor();

    @Test
    void extractsDocxParagraphAndTable(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("doc.docx");
        try (XWPFDocument doc = new XWPFDocument()) {
            doc.createParagraph().createRun().setText("HELLO_PARAGRAPH");
            var table = doc.createTable(1, 1);
            table.getRow(0).getCell(0).setText("CELL_MARKER");
            try (var out = Files.newOutputStream(file)) {
                doc.write(out);
            }
        }
        String text = extractor.extract(file);
        assertTrue(text.contains("HELLO_PARAGRAPH"), text);
        assertTrue(text.contains("CELL_MARKER"), text);
    }

    @Test
    void extractsXlsxCellsAcrossSheets(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("book.xlsx");
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            var s1 = wb.createSheet("Sheet1");
            s1.createRow(0).createCell(0).setCellValue("SHEET1_VALUE");
            var s2 = wb.createSheet("Sheet2");
            s2.createRow(0).createCell(0).setCellValue("SHEET2_VALUE");
            try (var out = Files.newOutputStream(file)) {
                wb.write(out);
            }
        }
        String text = extractor.extract(file);
        assertTrue(text.contains("SHEET1_VALUE"), text);
        assertTrue(text.contains("SHEET2_VALUE"), text);
    }

    @Test
    void extractsPptxSlideText(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("deck.pptx");
        try (XMLSlideShow ppt = new XMLSlideShow()) {
            XSLFSlide slide = ppt.createSlide();
            XSLFTextBox box = slide.createTextBox();
            box.setAnchor(new Rectangle(10, 10, 300, 100));
            box.setText("SLIDE_MARKER");
            try (var out = Files.newOutputStream(file)) {
                ppt.write(out);
            }
        }
        String text = extractor.extract(file);
        assertTrue(text.contains("SLIDE_MARKER"), text);
    }

    @Test
    void corruptFileThrows(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("broken.docx");
        Files.writeString(file, "this is not a valid OOXML package");
        assertThrows(Exception.class, () -> extractor.extract(file),
                "invalid OOXML must throw so the worker can fall back");
    }
}
