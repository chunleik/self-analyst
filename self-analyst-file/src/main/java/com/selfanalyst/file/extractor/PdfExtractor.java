package com.selfanalyst.file.extractor;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.nio.file.Path;

/**
 * PDF text extraction via Apache PDFBox (SPEC-FILE-014). Encrypted / corrupt
 * PDFs throw, leaving the worker to back off and fall back (SPEC-FILE-013b).
 */
public class PdfExtractor implements FileContentExtractor {

    @Override
    public String extract(Path file) throws Exception {
        try (PDDocument doc = Loader.loadPDF(file.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(doc);
            return text != null ? text : "";
        }
    }
}
