package com.selfanalyst.document;

import java.io.IOException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import javax.xml.stream.XMLInputFactory;

/** 发布前检查完整文件容器；不解析外部实体或加载外部资源。 */
final class DocumentFormatVerifier {
    static void verify(DocumentFormat format, Path path, DocumentBudget budget) throws IOException {
        budget.check();
        if (Files.size(path) > DocumentBudget.MAX_FILE_BYTES) throw new IOException("文档文件超限");
        switch (format) {
            case DOCX, XLSX, PPTX -> {
                String main = switch (format) {
                    case DOCX -> "word/document.xml";
                    case XLSX -> "xl/workbook.xml";
                    default -> "ppt/presentation.xml";
                };
                var xml = XMLInputFactory.newFactory();
                xml.setProperty(XMLInputFactory.SUPPORT_DTD, false);
                xml.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
                try (var zip = new ZipFile(path.toFile())) {
                    if (zip.getEntry(main) == null || zip.getEntry("[Content_Types].xml") == null) throw new IOException("Office 文件结构不完整");
                    long expanded = 0;
                    var entries = zip.entries();
                    while (entries.hasMoreElements()) {
                        budget.check(); var entry = entries.nextElement();
                        expanded += Math.max(0, entry.getSize());
                        if (expanded > 256L * 1024 * 1024) throw new IOException("Office 文档展开大小超限");
                        if (!entry.getName().endsWith(".xml") && !entry.getName().endsWith(".rels")) continue;
                        try (var input = zip.getInputStream(entry)) {
                            var parser = xml.createXMLStreamReader(input);
                            try { while (parser.hasNext()) { budget.check(); parser.next(); } }
                            finally { parser.close(); }
                        } catch (javax.xml.stream.XMLStreamException e) { throw new IOException("Office XML 不完整", e); }
                    }
                }
            }
            case PDF -> {
                try (var pdf = org.apache.pdfbox.Loader.loadPDF(path.toFile())) {
                    if (pdf.getNumberOfPages() < 1 || pdf.getNumberOfPages() > 200) throw new IOException("PDF 页数无效");
                }
            }
            case JSON -> {
                try (var parser = DocumentRequest.JSON.getFactory().createParser(path.toFile())) {
                    if (parser.nextToken() == null) throw new IOException("JSON 文档为空");
                    while (parser.nextToken() != null) budget.check();
                }
            }
            case CSV, MARKDOWN -> {
                var decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
                try (var reader = new java.io.InputStreamReader(Files.newInputStream(path), decoder)) {
                    char[] buffer = new char[8192]; while (reader.read(buffer) != -1) budget.check();
                }
            }
        }
        budget.check();
    }
}
