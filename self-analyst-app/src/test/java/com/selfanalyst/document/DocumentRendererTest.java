package com.selfanalyst.document;

import java.nio.file.*;
import java.awt.image.BufferedImage;
import java.util.concurrent.CancellationException;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.poi.xslf.usermodel.*;
import org.apache.poi.xwpf.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.CellType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DocumentRendererTest {
    @TempDir Path temp;
    private static final String TABLE = """
        {"name":"工作明细","columns":["标题","时长","数据"],"rows":[
          ["=危险公式",12.5,{"kind":"窗口"}],["中文，逗号\\n第二行",true,null]]}
        """;
    private static String source(boolean tableOnly) {
        return "{\"schemaVersion\":1,\"metadata\":{\"来源\":\"测试样例\"},"
                + (tableOnly ? "" : "\"blocks\":[{\"type\":\"heading\",\"text\":\"本周总结\"},"
                  + "{\"type\":\"paragraph\",\"text\":\"中文活动报告，包含原始记录与分析。\"},"
                  + "{\"type\":\"list\",\"items\":[\"完成需求分析\",\"继续验证\"]}],")
                + "\"sheets\":[" + TABLE + "]}";
    }
    private Path render(DocumentFormat format) throws Exception {
        Path path = temp.resolve("报告." + format.extension);
        new DocumentRenderer().render(DocumentRequest.parse(format.name(), "每周工作报告", source(format == DocumentFormat.CSV || format == DocumentFormat.XLSX)),
                path, new DocumentBudget(() -> false));
        assertTrue(Files.size(path) > 0);
        return path;
    }
    @Test void textFormatsPreserveValuesAndEscapePassiveContent() throws Exception {
        String csv = Files.readString(render(DocumentFormat.CSV));
        assertTrue(csv.contains("\"'=危险公式\""));
        assertTrue(csv.contains("\"中文，逗号\n第二行\""));
        var json = DocumentRequest.JSON.readTree(render(DocumentFormat.JSON).toFile());
        assertTrue(json.at("/sheets/0/rows/0/1").isNumber());
        assertEquals("窗口", json.at("/sheets/0/rows/0/2/kind").asText());
        String markdown = Files.readString(render(DocumentFormat.MARKDOWN));
        assertTrue(markdown.contains("## 本周总结"));
        assertTrue(markdown.contains("<br>第二行"));
    }
    @Test void officeFormatsContainEditableContent() throws Exception {
        try (var book = new XSSFWorkbook(render(DocumentFormat.XLSX).toFile())) {
            assertEquals(2, book.getNumberOfSheets());
            assertEquals(CellType.STRING, book.getSheetAt(0).getRow(1).getCell(0).getCellType());
            assertEquals("=危险公式", book.getSheetAt(0).getRow(1).getCell(0).getStringCellValue());
            assertEquals(12.5, book.getSheetAt(0).getRow(1).getCell(1).getNumericCellValue());
        }
        try (var input = Files.newInputStream(render(DocumentFormat.DOCX)); var word = new XWPFDocument(input)) {
            assertTrue(word.getParagraphs().stream().anyMatch(p -> p.getText().contains("中文活动报告")));
            assertEquals("=危险公式", word.getTables().getFirst().getRow(1).getCell(0).getText());
        }
        try (var input = Files.newInputStream(render(DocumentFormat.PPTX)); var deck = new XMLSlideShow(input)) {
            assertTrue(deck.getSlides().size() >= 3);
            assertTrue(deck.getSlides().stream().flatMap(s -> s.getShapes().stream()).anyMatch(XSLFTable.class::isInstance));
            assertTrue(deck.getSlides().stream().flatMap(s -> s.getShapes().stream()).filter(XSLFTextShape.class::isInstance)
                    .map(XSLFTextShape.class::cast).anyMatch(s -> s.getText().contains("中文活动报告")));
        }
    }
    @Test void pdfEmbedsChineseFontAndPreservesText() throws Exception {
        try (var pdf = Loader.loadPDF(render(DocumentFormat.PDF).toFile())) {
            String text = new PDFTextStripper().getText(pdf);
            assertTrue(text.contains("中文活动报告"), text);
            assertTrue(text.contains("危险公式"), text);
            for (var page : pdf.getPages()) for (var name : page.getResources().getFontNames())
                assertTrue(page.getResources().getFont(name).isEmbedded());
        }
    }
    @Test void cancellationAndExistingDestinationAreSafe() throws Exception {
        var request = DocumentRequest.parse("pdf", "报告", source(false));
        Path target = temp.resolve("old.pdf"); Files.writeString(target, "existing");
        assertThrows(FileAlreadyExistsException.class, () -> new DocumentRenderer().render(request, target, new DocumentBudget(() -> false)));
        assertEquals("existing", Files.readString(target));
        assertThrows(CancellationException.class, () -> new DocumentRenderer().render(request, temp.resolve("cancel.pdf"), new DocumentBudget(() -> true)));
    }
    @Test void longContentPaginatesAndProducesVisualSamples() throws Exception {
        String content = "中文长段落用于检查自动换行，所有内容都应完整保留。".repeat(120);
        String json = DocumentRequest.JSON.writeValueAsString(java.util.Map.of("schemaVersion", 1, "blocks",
                java.util.List.of(java.util.Map.of("type", "paragraph", "text", content))));
        for (DocumentFormat format : new DocumentFormat[]{DocumentFormat.DOCX, DocumentFormat.PDF, DocumentFormat.PPTX}) {
            Path file = temp.resolve("long." + format.extension);
            new DocumentRenderer().render(DocumentRequest.parse(format.name(), "中文分页验证", json), file, new DocumentBudget(() -> false));
            if (Boolean.getBoolean("document.visual.samples")) {
                Files.createDirectories(Path.of("target/document-samples"));
                Files.copy(file, Path.of("target/document-samples").resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            }
            if (format == DocumentFormat.PDF) try (var pdf = Loader.loadPDF(file.toFile())) {
                assertTrue(pdf.getNumberOfPages() > 1);
                String extracted = new PDFTextStripper().getText(pdf).replaceAll("\\s|[0-9]", "").replace("中文分页验证", "");
                assertEquals(content, extracted);
            }
        }
        if (Boolean.getBoolean("document.visual.samples")) {
            Path samples = Path.of("target/document-samples"); Files.createDirectories(samples);
            for (var format : DocumentFormat.values()) {
                Path file = render(format); Files.copy(file, samples.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                if (format == DocumentFormat.PDF) try (var pdf = Loader.loadPDF(file.toFile())) {
                    ImageIO.write(new PDFRenderer(pdf).renderImageWithDPI(0, 120), "png", samples.resolve("pdf.png").toFile());
                }
                if (format == DocumentFormat.PPTX) try (var in = Files.newInputStream(file); var deck = new XMLSlideShow(in)) {
                    java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(DocumentFont.awt(12));
                    int page = 0;
                    for (var slide : deck.getSlides()) {
                        var image = new BufferedImage(960, 540, BufferedImage.TYPE_INT_RGB);
                        var graphics = image.createGraphics(); graphics.setColor(java.awt.Color.WHITE); graphics.fillRect(0, 0, 960, 540);
                        slide.draw(graphics); graphics.dispose(); ImageIO.write(image, "png", samples.resolve("ppt-" + page++ + ".png").toFile());
                    }
                }
            }
        }
    }

    @Test void rendererBudgetsAndWideTablesFailExplicitly() throws Exception {
        String lines = "行\n".repeat(12000);
        String source = DocumentRequest.JSON.writeValueAsString(java.util.Map.of("schemaVersion", 1, "blocks",
                java.util.List.of(java.util.Map.of("type", "paragraph", "text", lines))));
        for (var format : new DocumentFormat[]{DocumentFormat.DOCX, DocumentFormat.PDF, DocumentFormat.PPTX}) {
            assertThrows(IllegalArgumentException.class, () -> new DocumentRenderer().render(DocumentRequest.parse(format.name(), "页数限制", source),
                    temp.resolve("limit." + format.extension), new DocumentBudget(() -> false)));
        }
        assertThrows(IllegalArgumentException.class, () -> new DocumentBudget(() -> false, java.time.Duration.ZERO).check());
    }

    @Test void multiPageTablesAndLargeWorkbookPreserveEveryRow() throws Exception {
        var root = DocumentRequest.JSON.createObjectNode(); root.put("schemaVersion", 1);
        var sheet = root.putArray("sheets").addObject(); sheet.put("name", "长表格"); sheet.putArray("columns").add("序号").add("记录内容");
        var rows = sheet.putArray("rows");
        for (int i = 0; i < 500; i++) rows.addArray().add(i).add("中文记录第" + i + "条，保留完整内容。");
        Path workbook = temp.resolve("large.xlsx");
        new DocumentRenderer().render(DocumentRequest.parse("xlsx", "大表", root.toString()), workbook, new DocumentBudget(() -> false));
        try (var book = new XSSFWorkbook(workbook.toFile())) {
            assertEquals(500, book.getSheetAt(0).getLastRowNum());
            assertTrue(book.getSheetAt(0).getRow(500).getCell(1).getStringCellValue().contains("第499条"));
        }
        while (rows.size() > 60) rows.remove(rows.size() - 1);
        for (var format : new DocumentFormat[]{DocumentFormat.DOCX, DocumentFormat.PDF, DocumentFormat.PPTX}) {
            Path file = temp.resolve("table." + format.extension);
            new DocumentRenderer().render(DocumentRequest.parse(format.name(), "表格分页验证", root.toString()), file, new DocumentBudget(() -> false));
            if (format == DocumentFormat.PDF) try (var pdf = Loader.loadPDF(file.toFile())) {
                assertTrue(pdf.getNumberOfPages() > 1); assertTrue(new PDFTextStripper().getText(pdf).contains("第59条"));
            }
            if (Boolean.getBoolean("document.visual.samples")) Files.copy(file, Path.of("target/document-samples").resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
