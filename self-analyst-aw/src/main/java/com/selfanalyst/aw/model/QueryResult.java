package com.selfanalyst.aw.model;

import java.util.List;
import java.util.Map;

public record QueryResult(
        List<Map<String, Object>> rows,
        double totalDuration,
        int totalEvents) {

    public Map<String, Object> toMap() {
        return Map.of(
                "rows", (Object) rows,
                "total_duration", totalDuration,
                "total_events", totalEvents);
    }
}
