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
        String contextKind,
        String titleSource,
        String titleConfidence,
        int uiaChars) {

    public Map<String, Object> toHeartbeatData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("schema_version", 2);
        data.put("app", app != null ? app : "");
        data.put("title", title != null ? title : "");
        if (contextTitle != null && !contextTitle.isBlank()) {
            data.put("context_title", contextTitle);
            data.put("context_kind", contextKind != null ? contextKind : "unknown");
            if (titleConfidence != null && !titleConfidence.isBlank()) {
                data.put("title_confidence", titleConfidence);
            }
        }
        data.put("title_source", titleSource != null ? titleSource : "window");
        data.put("uia_chars", uiaChars);
        return Collections.unmodifiableMap(data);
    }
}
