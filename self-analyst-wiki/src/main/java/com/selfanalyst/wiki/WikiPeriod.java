package com.selfanalyst.wiki;

import java.time.Instant;

public record WikiPeriod(
        WikiLevel level,
        Instant start,
        Instant end,
        String timezone) {
}
