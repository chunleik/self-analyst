package com.selfanalyst.document;

import java.util.Locale;

public enum DocumentFormat {
    CSV("csv", "text/csv; charset=utf-8"),
    JSON("json", "application/json"),
    MARKDOWN("md", "text/markdown; charset=utf-8"),
    HTML("html", "text/html; charset=utf-8"),
    SVG("svg", "image/svg+xml"),
    XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    PDF("pdf", "application/pdf"),
    PPTX("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation");

    public final String extension;
    public final String mime;

    DocumentFormat(String extension, String mime) {
        this.extension = extension;
        this.mime = mime;
    }

    public static DocumentFormat parse(String value) {
        if (value == null) throw new IllegalArgumentException("请选择文档格式");
        if (value.equalsIgnoreCase("md")) return MARKDOWN;
        try { return valueOf(value.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("不支持的文档格式"); }
    }
}
