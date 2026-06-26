package com.selfanalyst.file.extractor;

import org.apache.poi.extractor.ExtractorFactory;
import org.apache.poi.extractor.POITextExtractor;
import org.apache.poi.openxml4j.util.ZipSecureFile;

import java.nio.file.Path;

/**
 * Modern OOXML office documents — .docx/.xlsx/.pptx — via Apache POI
 * (SPEC-FILE-018). Legacy binary .doc/.xls/.ppt are NOT handled here
 * (SPEC-FILE-018b); they fall through to {@link MetadataOnlyExtractor}.
 *
 * <p>A single {@link ExtractorFactory#createExtractor(java.io.File)} entry point
 * detects the concrete format by content and returns the matching
 * {@link POITextExtractor} (SPEC-FILE-018d). The original text is returned
 * untruncated (SPEC-FILE-018f).
 */
public class OfficeExtractor implements FileContentExtractor {

    static {
        // SPEC-FILE-014c / SPEC-FILE-018l: relax POI's zip-bomb ratio so that
        // legitimate, highly-compressible OOXML files are not falsely rejected.
        ZipSecureFile.setMinInflateRatio(0.001d);
    }

    @Override
    public String extract(Path file) throws Exception {
        // try-with-resources closes the underlying OPCPackage + file handle
        // (SPEC-FILE-018e). Any POI exception propagates to the worker.
        try (POITextExtractor extractor = ExtractorFactory.createExtractor(file.toFile())) {
            String text = extractor.getText();
            return text != null ? text : "";
        }
    }
}
