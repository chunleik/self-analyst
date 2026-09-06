package com.selfanalyst.desktop.service;

import java.time.Instant;

/**
 * Local activity facts used by desktop summary assembly.
 */
public interface SummaryFactSource {

    SummaryService.LocalFacts currentStatus(Instant now);

    SummaryService.LocalFacts factsFor(Instant start, Instant end, String label);

    SummaryService.BehaviorData behaviorData();
}
