package com.selfanalyst.content;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record ContentEvent(
        Instant timestamp,
        double duration,
        String app,
        String title,
        String contextTitle,
        String textContent,
        String source,
        int uiaChars,
        int ocrChars) {

    /** Backwards-compatible constructor for events without semantic context. */
    public ContentEvent(Instant timestamp, double duration, String app, String title,
                        String textContent, String source, int uiaChars, int ocrChars) {
        this(timestamp, duration, app, title, null, textContent, source, uiaChars, ocrChars);
    }

    public Map<String, Object> toHeartbeatData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("app", app != null ? app : "");
        data.put("title", title != null ? title : "");
        if (contextTitle != null && !contextTitle.isBlank()) {
            data.put("context_title", contextTitle);
        }
        data.put("text_content", textContent != null ? textContent : "");
        data.put("source", source != null ? source : "uia");
        data.put("uia_chars", uiaChars);
        data.put("ocr_chars", ocrChars);
        return Collections.unmodifiableMap(data);
    }
}
