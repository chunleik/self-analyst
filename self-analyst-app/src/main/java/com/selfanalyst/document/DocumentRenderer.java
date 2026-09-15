package com.selfanalyst.document;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class DocumentRenderer {
    /** 仅接收调用方分配的临时路径；调用方负责成功发布。 */
    public void render(DocumentRequest request, Path target, DocumentBudget budget) throws IOException {
        try (var output = budget.bound(Files.newOutputStream(target, StandardOpenOption.CREATE_NEW))) {
            switch (request.format()) {
                case CSV, JSON, MARKDOWN -> new TextDocumentRenderer().render(request, output, budget);
                case HTML, SVG -> new MarkupDocumentRenderer().render(request, output, budget);
                case XLSX -> new SpreadsheetDocumentRenderer().render(request, output, budget);
                case DOCX -> new WordDocumentRenderer().render(request, output, budget);
                case PDF -> new PdfDocumentRenderer().render(request, output, budget);
                case PPTX -> new PresentationDocumentRenderer().render(request, output, budget);
            }
        } catch (Exception e) {
            // 仅删除本次成功创建的临时文件；已有路径由 CREATE_NEW 拒绝，不能删除。
            throw e;
        }
    }
}
