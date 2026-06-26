package com.selfanalyst.file.extractor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FileContentExtractorFactoryTest {

    private final FileContentExtractorFactory factory = new FileContentExtractorFactory();

    @Test
    void ooxmlMapsToOfficeExtractor() {
        assertInstanceOf(OfficeExtractor.class, factory.forExtension("docx"));
        assertInstanceOf(OfficeExtractor.class, factory.forExtension("xlsx"));
        assertInstanceOf(OfficeExtractor.class, factory.forExtension("pptx"));
    }

    @Test
    void legacyBinaryOfficeFallsBackToMetadataOnly() {
        assertInstanceOf(MetadataOnlyExtractor.class, factory.forExtension("doc"));
        assertInstanceOf(MetadataOnlyExtractor.class, factory.forExtension("xls"));
        assertInstanceOf(MetadataOnlyExtractor.class, factory.forExtension("ppt"));
    }

    @Test
    void textAndCodeAndPdfMapped() {
        assertInstanceOf(PlainTextExtractor.class, factory.forExtension("md"));
        assertInstanceOf(SourceCodeExtractor.class, factory.forExtension("java"));
        assertInstanceOf(PdfExtractor.class, factory.forExtension("pdf"));
    }

    @Test
    void unknownBinaryFallsBackToMetadataOnly() {
        assertInstanceOf(MetadataOnlyExtractor.class, factory.forExtension("bin"));
        assertInstanceOf(MetadataOnlyExtractor.class, factory.forExtension(""));
    }

    @Test
    void extensionCaseInsensitive() {
        assertInstanceOf(OfficeExtractor.class, factory.forExtension("DOCX"));
    }
}
