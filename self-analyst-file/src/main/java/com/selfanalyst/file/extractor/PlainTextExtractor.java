package com.selfanalyst.file.extractor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Plain text / structured-text files: .txt/.md/.csv/.json/.xml/.yaml… (SPEC-FILE-014).
 * Reads as UTF-8, tolerating malformed bytes rather than failing.
 */
public class PlainTextExtractor implements FileContentExtractor {

    @Override
    public String extract(Path file) throws Exception {
        byte[] bytes = Files.readAllBytes(file);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
