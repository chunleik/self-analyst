package com.selfanalyst.file.extractor;

import java.util.Map;
import java.util.Set;

/**
 * Selects a {@link FileContentExtractor} by lower-cased extension (SPEC-FILE-014).
 * Unknown / legacy-binary extensions (incl. .doc/.xls/.ppt per SPEC-FILE-018b)
 * fall back to {@link MetadataOnlyExtractor} — never an error.
 *
 * <p>Extractors are stateless apart from {@link ImageExtractor} (holds a
 * Tesseract handle); a single instance of each is created lazily and reused.
 */
public class FileContentExtractorFactory {

    private static final Set<String> SOURCE_CODE = Set.of(
            "java", "py", "js", "ts", "tsx", "jsx", "go", "rs", "kt", "kts",
            "c", "h", "cpp", "hpp", "cc", "cs", "rb", "php", "swift", "scala",
            "sh", "bash", "ps1", "sql", "gradle", "groovy", "lua", "r", "dart");

    private static final Set<String> PLAIN_TEXT = Set.of(
            "txt", "md", "markdown", "csv", "tsv", "json", "xml", "yaml", "yml",
            "toml", "ini", "cfg", "conf", "properties", "html", "htm", "rst", "tex", "log");

    private static final Set<String> PDF = Set.of("pdf");
    private static final Set<String> OFFICE = Set.of("docx", "xlsx", "pptx");
    private static final Set<String> IMAGE = Set.of(
            "png", "jpg", "jpeg", "bmp", "gif", "tif", "tiff", "webp");

    private final FileContentExtractor plainText = new PlainTextExtractor();
    private final FileContentExtractor sourceCode = new SourceCodeExtractor();
    private final FileContentExtractor pdf = new PdfExtractor();
    private final FileContentExtractor office = new OfficeExtractor();
    private final FileContentExtractor metadataOnly = new MetadataOnlyExtractor();
    private volatile ImageExtractor image; // lazy: avoids Tesseract probe when no images

    /** @return an extractor for {@code extension} (never null). */
    public FileContentExtractor forExtension(String extension) {
        String ext = extension == null ? "" : extension.toLowerCase();
        if (PLAIN_TEXT.contains(ext)) return plainText;
        if (SOURCE_CODE.contains(ext)) return sourceCode;
        if (PDF.contains(ext)) return pdf;
        if (OFFICE.contains(ext)) return office;
        if (IMAGE.contains(ext)) return imageExtractor();
        return metadataOnly;
    }

    public FileContentExtractor metadataOnly() {
        return metadataOnly;
    }

    /** True if a dedicated (non-fallback) extractor exists for the extension. */
    public boolean isExtractable(String extension) {
        String ext = extension == null ? "" : extension.toLowerCase();
        return PLAIN_TEXT.contains(ext) || SOURCE_CODE.contains(ext)
                || PDF.contains(ext) || OFFICE.contains(ext) || IMAGE.contains(ext);
    }

    /** Supported extensions per category — used to advertise capabilities/tests. */
    public Map<String, Set<String>> supported() {
        return Map.of(
                "plainText", PLAIN_TEXT,
                "sourceCode", SOURCE_CODE,
                "pdf", PDF,
                "office", OFFICE,
                "image", IMAGE);
    }

    private ImageExtractor imageExtractor() {
        ImageExtractor local = image;
        if (local == null) {
            synchronized (this) {
                local = image;
                if (local == null) {
                    local = new ImageExtractor();
                    image = local;
                }
            }
        }
        return local;
    }
}
