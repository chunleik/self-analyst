package com.selfanalyst.file.extractor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Source code files: .java/.py/.js/.ts/.go/.rs/.kt… (SPEC-FILE-014).
 * Source is plain UTF-8 text; kept as a distinct type for factory clarity.
 */
public class SourceCodeExtractor implements FileContentExtractor {

    @Override
    public String extract(Path file) throws Exception {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
