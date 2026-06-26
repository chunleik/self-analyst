package com.selfanalyst.aw.model;

import java.time.Instant;
import java.util.Map;

public record Event(
        long id,
        Instant timestamp,
        double duration,
        Map<String, Object> data) {

    public Event(Instant timestamp, double duration, Map<String, Object> data) {
        this(0, timestamp, duration, data);
    }
}
