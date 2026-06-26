package com.selfanalyst.file.extractor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Binary / unsupported fallback (SPEC-FILE-014, SPEC-FILE-013b). Never throws on
 * content; emits only filesystem metadata so the file still gets a summary row.
 */
public class MetadataOnlyExtractor implements FileContentExtractor {

    @Override
    public String extract(Path file) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("文件名: ").append(file.getFileName()).append("\n");
        try {
            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
            sb.append("大小: ").append(attrs.size()).append(" 字节\n");
            sb.append("最后修改: ").append(attrs.lastModifiedTime()).append("\n");
            sb.append("创建时间: ").append(attrs.creationTime()).append("\n");
        } catch (Exception ignored) {
            // metadata best-effort
        }
        sb.append("说明: 二进制或不支持的文件类型，仅提取元数据。");
        return sb.toString();
    }
}
