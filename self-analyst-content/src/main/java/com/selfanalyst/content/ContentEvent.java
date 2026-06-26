package com.selfanalyst.content;

import java.time.Instant;
import java.util.Map;

public record ContentEvent(
        Instant timestamp,
        double duration,
        String app,
        String title,
        String textContent,
        String source,
        int uiaChars,
        int ocrChars) {

    public Map<String, Object> toHeartbeatData() {
        return Map.of(
                "app", (Object) (app != null ? app : ""),
                "title", (Object) (title != null ? title : ""),
                "text_content", (Object) (textContent != null ? textContent : ""),
                "source", (Object) (source != null ? source : "uia"),
                "uia_chars", uiaChars,
                "ocr_chars", ocrChars);
    }
}
